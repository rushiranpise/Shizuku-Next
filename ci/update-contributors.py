#!/usr/bin/env python3
"""Regenerate the contributor credits from this repository's own history.

The wall is drawn from a JSON snapshot. Nothing in it is typed by hand, so the credits track the
repository instead of drifting away from it: a new contributor appears, an existing one gains
commits and areas, and a translator picks up the language they worked on, all by running this and
committing the result. The GitHub Action runs it on a schedule, which is what keeps the wall
current without anybody thinking about it.

    python ci/update-contributors.py             regenerate the snapshot
    python ci/update-contributors.py --check     fail if it is out of date (for CI)
    python ci/update-contributors.py --no-network   keep known accounts, fetch nothing

What is derived, and from where:

  commits      `git shortlog` over authored, non-merge commits reachable from HEAD. Only HEAD: an
               author whose work sits on another branch is not credited for something this app does
               not contain, and counting every ref would double-count what a fork shares with its
               upstream.
  areas        the paths each person's commits touched, so a badge is a fact and not a guess.
  languages    the `values-<lang>` directories they touched, which is what a translator did.
  account      the GitHub account behind a commit email, asked of the API. Where an email is not
               tied to an account there is no photo and the wall draws the person's initial.

Everyone with a commit in HEAD is included, however few. The only table below is the one thing git
cannot tell us: which several commit identities are the same person.

Pictures are not carried in the app: the snapshot points at each account's avatar on GitHub, and
the app fetches and caches them itself. So this needs no image library, and the repository grows
no binary.

Requires: git; `gh` (authenticated) only to resolve accounts.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_ROOT = os.path.join(REPO_ROOT, "manager", "src", "main", "assets")
OUTPUT = os.path.join(ASSETS_ROOT, "contributors.json")

# Several commit identities, one person. Keys are the display name the wall shows.
ALIASES = {
    "Rikka": ["rikka@shizuku.moe", "rikka@xing.moe"],
    "Rushi Ranpise": ["rushiranpise17@gmail.com", "37355997+rushiranpise@users.noreply.github.com"],
    "thedjchi": ["achiommino@gmail.com", "80229487+thedjchi@users.noreply.github.com"],
    "ZQY": ["22442525+ryuunoakaihitomi@users.noreply.github.com"],
    "Haruue Icymoon": ["haruue@caoyue.com.cn", "i@haruue.moe"],
    # The same account under the name it used before it renamed: the noreply address is the same
    # person's, and without this they are two faces on the wall with one picture between them.
    "heruoxin": ["heruoxin@gmail.com", "heruoxin@users.noreply.github.com"],
}

# Accounts that commit here but are not people: release automation and the translation import.
EXCLUDED_EMAILS = {
    "github-actions[bot]@users.noreply.github.com",
    "41898282+github-actions[bot]@users.noreply.github.com",
    "noreply@weblate.org",
    "noreply@anthropic.com",
}
EXCLUDED_NAMES = re.compile(r"\[bot\]|^weblate$|^anonymous$|^github-actions", re.IGNORECASE)

# Handles that cannot be derived from the commit email, because the email is not attached to the
# account. Checked against GitHub by hand; without them the wall would either open a stranger's
# profile or show an initial for someone who does have an account.
LOGIN_OVERRIDES = {
    "Ryfter": "Ryfter",
    "IverCoder": "IverCoder",
}

# Order matters: the first area that matches wins.
AREA_ORDER = ["APP", "SERVER", "ADB", "BUILD", "I18N", "DOCS"]

# Android resource qualifiers are not BCP-47 tags: zh-rTW is written zh-TW.
QUALIFIER = re.compile(r"values-([a-zA-Z]{2,3})(?:-r([A-Z]{2}))?/")


def area_of(path: str) -> str | None:
    lowered = path.lower()
    name = lowered.rsplit("/", 1)[-1]

    # Nothing here is part of the app: automation, and scratch trees kept beside it.
    if lowered.startswith(".github/") or lowered.startswith("ci/"):
        return None
    if lowered.startswith("tmp/") or lowered.startswith("shevery/"):
        return None

    # Checked before the code areas so that, say, a translated string file under the manager still
    # reads as a translation rather than as app work.
    if QUALIFIER.search(path) or "crowdin" in lowered:
        return "I18N"
    if lowered.startswith("docs/") or lowered.endswith(".md"):
        return "DOCS"
    if (name.endswith(".gradle") or name.endswith(".gradle.kts") or name == "settings.gradle"
            or name.startswith("gradlew") or lowered.startswith("gradle/")):
        return "BUILD"
    if "/adb/" in lowered or name.startswith("adb"):
        return "ADB"
    # Prefixes, not substrings: `manager/src/main/jni/` is the manager's own native code and was
    # being counted as server work, which moved real contributors' badges around.
    if lowered.startswith("server/") or lowered.startswith("jni/"):
        return "SERVER"
    if lowered.startswith("manager/"):
        return "APP"
    return None


def language_of(path: str) -> str | None:
    match = QUALIFIER.search(path)
    if not match:
        return None
    base, region = match.group(1), match.group(2)
    language = f"{base}-{region}" if region else base
    # English is the source language, so translating into it is not a contribution to claim.
    return None if language == "en" or language.startswith("en-") else language


def git_history() -> tuple[
    collections.Counter, dict[str, str], dict[str, collections.Counter],
    dict[str, collections.Counter], dict[str, str]
]:
    """Every author in HEAD, with the areas and languages their commits touched."""
    out = subprocess.run(
        ["git", "-C", REPO_ROOT, "log", "--no-merges", "--format=__A__%ae\t%an", "--name-only",
         "HEAD"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout

    commits: collections.Counter = collections.Counter()
    names: dict[str, str] = {}
    areas: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    languages: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)

    email = None
    for line in out.splitlines():
        if line.startswith("__A__"):
            _, _, rest = line.partition("__A__")
            address, _, author = rest.partition("\t")
            email = address.strip().lower()
            # First name wins: an identity that renamed itself mid-history keeps its oldest name,
            # which is the one people would recognise.
            names.setdefault(email, author.strip() or address.strip())
            commits[email] += 1
        elif line.strip() and email:
            path = line.strip()
            area = area_of(path)
            if area:
                areas[email][area] += 1
            language = language_of(path)
            if language:
                languages[email][language] += 1
    return commits, names, areas, languages, {}


def resolve_account(email: str, remote: str) -> tuple[str | None, str | None]:
    """Which GitHub account, and which picture, this commit email belongs to."""
    if shutil.which("gh") is None:
        return None, None
    query = urllib.parse.quote(email, safe="")
    result = subprocess.run(
        ["gh", "api", f"repos/{remote}/commits?author={query}&per_page=1",
         "--jq", '.[0].author | [(.login // ""), (.avatar_url // "")] | @tsv'],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    parts = result.stdout.strip().split("\t")
    if len(parts) != 2 or not parts[0]:
        return None, None
    return parts[0], parts[1] or None


def remote_slug() -> str:
    url = subprocess.run(
        ["git", "-C", REPO_ROOT, "remote", "get-url", "origin"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout.strip()
    match = re.search(r"github\.com[:/](.+?)(?:\.git)?$", url)
    return match.group(1) if match else "rushiranpise/Shizuku-Next"


def known_accounts() -> dict[str, tuple[str | None, str | None]]:
    """Accounts already recorded in the snapshot, so a run without the network changes nothing."""
    if not os.path.isfile(OUTPUT):
        return {}
    try:
        with open(OUTPUT, encoding="utf-8") as handle:
            payload = json.load(handle)
    except (OSError, ValueError):
        return {}
    found = {}
    for entry in payload.get("contributors", []):
        key = entry.get("name") or entry.get("login")
        if key:
            found[key] = (entry.get("login"), entry.get("avatarUrl"))
    return found


def merge_same_account(people: list[dict]) -> list[dict]:
    """One account is one person.

    Two commit identities can resolve to the same GitHub account. The alias table above folds the
    ones that are known, but the account itself is the real answer - and left alone, that person is
    credited twice: two faces on the wall holding one picture, and counted twice in the badge.
    """
    merged: dict[str, dict] = {}
    for person in people:
        key = person["login"] or person["name"]
        keeper = merged.get(key)
        if keeper is None:
            merged[key] = person
            continue
        # The busier identity names the person, the way the alias table does it by hand.
        if person["commits"] > keeper["commits"]:
            keeper, person = person, keeper
        keeper["commits"] += person["commits"]
        areas = dict.fromkeys(keeper["areas"] + person["areas"])
        keeper["areas"] = sorted(areas, key=AREA_ORDER.index)[:3]
        keeper["languages"] = list(dict.fromkeys(keeper["languages"] + person["languages"]))[:3]
        keeper["avatarUrl"] = keeper["avatarUrl"] or person["avatarUrl"]
        merged[key] = keeper

    return sorted(merged.values(), key=lambda p: (-p["commits"], p["name"]))


def build(network: bool, remote: str) -> dict:
    commits, names, areas, languages, _ = git_history()
    known = known_accounts()

    # Fold the identities we know belong together, then take everything that is left as itself.
    folded: dict[str, list[str]] = {}
    for display, emails in ALIASES.items():
        folded[display] = [e.lower() for e in emails]
    assigned = {e for emails in folded.values() for e in emails}
    for email in commits:
        if email in assigned or email in EXCLUDED_EMAILS:
            continue
        folded.setdefault(names.get(email, email), []).append(email)

    people = []
    for display, emails in folded.items():
        if EXCLUDED_NAMES.search(display):
            continue
        total = sum(commits.get(email, 0) for email in emails)
        if total == 0:
            continue

        touched: collections.Counter = collections.Counter()
        spoken: collections.Counter = collections.Counter()
        for email in emails:
            touched.update(areas.get(email, {}))
            spoken.update(languages.get(email, {}))

        ranked = sorted(touched, key=lambda key: (-touched[key], AREA_ORDER.index(key)))
        # Three badges is what fits on one row of the card.
        areas_for_person = ranked[:3]

        # A language is only claimed for someone whose work is mostly translation. Anyone who
        # writes code in this project also touches the Chinese string files, and several people
        # have run bulk updates across every locale at once - read off the paths alone, that made a
        # maintainer look like a translator of two hundred languages. The count is capped for the
        # same reason: past a handful it is bulk work, not a language someone speaks.
        spoken_languages = (
            [lang for lang, _ in spoken.most_common(3)]
            if areas_for_person[:1] == ["I18N"] else []
        )

        people.append({
            "name": display,
            "login": None,
            "avatarUrl": None,
            "commits": total,
            "areas": areas_for_person,
            "languages": spoken_languages,
            "_emails": emails,
        })

    people.sort(key=lambda p: (-p["commits"], p["name"]))

    for person in people:
        recorded = known.get(person["name"], (None, None))
        login = LOGIN_OVERRIDES.get(person["name"]) or recorded[0]
        avatar_url = recorded[1]
        for email in person["_emails"]:
            if login:
                break
            if network:
                login, avatar_url = resolve_account(email, remote)

        # The picture is the account's own, at its own URL: nothing is carried in the app, and
        # whoever has no account has no picture either, which the wall draws as their initial.
        person["login"] = login
        person["avatarUrl"] = avatar_url or (
            f"https://github.com/{login}.png?size=256" if login else None
        )
        person.pop("_emails")

    people = merge_same_account(people)

    return {
        "generated": subprocess.run(
            ["git", "-C", REPO_ROOT, "log", "-1", "--format=%cs", "HEAD"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        ).stdout.strip(),
        "contributors": people,
    }


def render(payload: dict) -> str:
    # Indented and newline-terminated so the diff of a refresh is readable in review.
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero if the snapshot is out of date")
    parser.add_argument("--no-network", action="store_true",
                        help="do not ask GitHub or download avatars")
    parser.add_argument("--out", default=OUTPUT, help="where to write the snapshot")
    args = parser.parse_args()

    content = render(build(network=not (args.no_network or args.check), remote=remote_slug()))

    if args.check:
        current = open(args.out, encoding="utf-8").read() if os.path.isfile(args.out) else ""
        if current != content:
            print("Contributor credits are out of date. Run: python ci/update-contributors.py",
                  file=sys.stderr)
            return 1
        print("Contributor credits are up to date.")
        return 0

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)

    total = len(json.loads(content)["contributors"])
    print(f"Wrote {os.path.relpath(args.out, REPO_ROOT)} with {total} contributors")
    return 0


if __name__ == "__main__":
    sys.exit(main())
