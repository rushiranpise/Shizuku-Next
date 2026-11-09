#!/usr/bin/env python3
"""Remove only the translations the check script refuses, before they are merged.

The nightly sync downloads whatever is in Crowdin. If a translator has changed a
placeholder or an ADB command, `check-translations.py` refuses the pull request and the
automatic merge stops. That is the right result, but it leaves the same bad translation in
Crowdin, so the next sync brings it back and opens another pull request.

This script closes that loop. It reads the same changed-list, translated-ref and source-ref
as the check, finds the strings whose placeholders or ADB commands no longer match or that a
pull request emptied, removes those elements from the locale files, and deletes their
translations from Crowdin. Removing an element makes Android fall back to the English
string, which is valid and cannot be reintroduced by the next download.

    repair-translations.py <changed-list> <translated-ref> <source-ref>

Pass `--no-crowdin` to repair the working tree without touching Crowdin. Pass `--dry-run`
to print what would change. The CI workflow uses neither flag, so a repair that cannot be
completed stops the merge instead of silently leaving Crowdin with the bad translation.
"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
CHECK_PATH = HERE / "check-translations.py"
SPEC = importlib.util.spec_from_file_location("check_translations", CHECK_PATH)
CHECKS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKS)

SOURCE_PATH = "manager/src/main/res/values/strings.xml"
CROWDIN_API = "https://api.crowdin.com/api/v2"
CROWDIN_PROJECT_ID = os.environ.get("CROWDIN_PROJECT_ID", "935085")

# A localized string or plural. The opening tag is matched non-greedily so a self-closing
# element does not run on to the next closing tag. The body cannot contain another element
# with the same tag in an Android resource file.
ELEMENT = re.compile(
    r"(?ms)^[ \t]*<(string|plurals)\b(?P<attrs>[^>]*?)"
    r"(?:/>|>(?P<body>.*?)</\1>)[ \t]*(?:\r?\n)?"
)
NAME = re.compile(r'\bname\s*=\s*"([^"]*)"')
LOCALE = re.compile(r"/values-([^/]+)/strings\.xml$")


class RepairError(RuntimeError):
    """A translation could not be removed from the branch or from Crowdin."""


def android_language_map(path):
    """Return Android locale code -> Crowdin language id from `crowdin.yml`.

    PyYAML is used when it is installed. The fallback reads the one mapping block the
    script needs, so a minimal GitHub runner does not need an extra dependency.
    """
    text = path.read_text(encoding="utf-8")
    try:
        import yaml
    except ImportError:
        yaml = None

    if yaml is not None:
        data = yaml.safe_load(text)
        mapping = data["files"][0]["languages_mapping"]["android_code"]
        return {str(android): str(crowdin) for crowdin, android in mapping.items()}

    lines = text.splitlines()
    start = next(
        (
            index
            for index, line in enumerate(lines)
            if re.match(r"^[ \t]*android_code:\s*$", line)
        ),
        None,
    )
    if start is None:
        raise RepairError("crowdin.yml has no android_code mapping")

    indent = len(lines[start]) - len(lines[start].lstrip())
    mapping = {}
    for line in lines[start + 1 :]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        current_indent = len(line) - len(line.lstrip())
        if current_indent <= indent:
            break
        match = re.match(
            r'^[ \t]*("?[^":]+"?)\s*:\s*("?[^"#\s]+"?)\s*(?:#.*)?$',
            line,
        )
        if not match:
            continue
        crowdin = match.group(1).strip('"')
        android = match.group(2).strip('"')
        mapping[android] = crowdin
    if not mapping:
        raise RepairError("crowdin.yml has an empty android_code mapping")
    return mapping


def crowdin_language(path, languages):
    match = LOCALE.search(path)
    if not match:
        return None
    android = match.group(1)
    try:
        return languages[android]
    except KeyError as error:
        raise RepairError(f"{path}: no Crowdin language mapped for values-{android}") from error


def find_bad_keys(paths, translated_ref, source_ref):
    """Return path -> base element names whose translation the gate rejects."""
    source_texts = CHECKS.texts_at(source_ref, SOURCE_PATH)
    source_commands = CHECKS.commands_at(source_ref, SOURCE_PATH)
    bad = {}

    for path in paths:
        if not CHECKS.LOCALE_PATH.match(path):
            print(f"{path}: not a translation file; leaving it for the check")
            continue
        try:
            translated_texts = CHECKS.texts_at(translated_ref, path)
        except ElementTree.ParseError as error:
            print(f"{path}: not valid XML; leaving it for the check ({error})")
            continue
        if not translated_texts:
            continue
        try:
            base_texts = CHECKS.texts_at(source_ref, path)
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            base_texts = {}

        rejected = set()
        for key, source_value in source_texts.items():
            translated_value = translated_texts.get(key)
            if translated_value is None:
                continue

            source_placeholders = CHECKS.format_placeholders(source_value)
            translated_placeholders = CHECKS.format_placeholders(translated_value)
            if source_placeholders != translated_placeholders:
                rejected.add(key.split("[", 1)[0])

            if (
                source_value.strip()
                and not translated_value.strip()
                and (key not in base_texts or base_texts[key].strip())
            ):
                rejected.add(key.split("[", 1)[0])

            for command in source_commands.get(key, []):
                if command not in CHECKS.normalize_text(translated_value):
                    rejected.add(key.split("[", 1)[0])
                    break

        if rejected:
            bad[path] = rejected

    return bad


def remove_elements(text, names):
    """Remove string/plurals elements named in `names`, preserving the rest of the file."""
    removed = []

    def replace(match):
        name = NAME.search(match.group("attrs"))
        if name is None or name.group(1) not in names:
            return match.group(0)
        removed.append(name.group(1))
        return ""

    fixed = ELEMENT.sub(replace, text)
    ElementTree.fromstring(fixed)
    return fixed, removed


def api_request(method, path, token, params=None):
    """Call Crowdin without exposing the token, and return decoded JSON or None for 204."""
    url = f"{CROWDIN_API}/projects/{CROWDIN_PROJECT_ID}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.status == 204:
                return None
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        raise RepairError(
            f"Crowdin {method} {path} returned {error.code}: {body[:500]}"
        ) from error


def find_string_id(identifier, token):
    """Find the Crowdin source string id for a key, including a plural's base key."""
    candidates = [identifier]
    base = identifier.split("[", 1)[0]
    if base != identifier:
        candidates.append(base)

    for candidate in candidates:
        payload = api_request(
            "GET",
            "/strings",
            token,
            {"filter": candidate, "scope": "identifier", "limit": 500},
        )
        for item in payload.get("data", []):
            data = item.get("data", {})
            if data.get("identifier") == candidate:
                return data.get("id")
    return None


def remove_from_crowdin(path, keys, languages, token):
    """Delete every translation of each rejected key in its Crowdin language."""
    language = crowdin_language(path, languages)
    if language is None:
        print(f"{path}: no locale language; removed from the branch only")
        return

    for key in sorted(keys):
        string_id = find_string_id(key, token)
        if string_id is None:
            print(f"{path}: {key}: not found in Crowdin; removed from the branch only")
            continue

        translations = api_request(
            "GET",
            "/translations",
            token,
            {"stringId": string_id, "languageId": language},
        )
        count = len(translations.get("data", [])) if translations else 0
        api_request(
            "DELETE",
            "/translations",
            token,
            {"stringId": string_id, "languageId": language},
        )
        print(f"{path}: {key}: removed {count} Crowdin translation(s) for {language}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("changed_list")
    parser.add_argument("translated_ref")
    parser.add_argument("source_ref")
    parser.add_argument(
        "--no-crowdin",
        action="store_true",
        help="repair the working tree without deleting anything from Crowdin",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the rejected keys without changing files or Crowdin",
    )
    args = parser.parse_args()

    with open(args.changed_list, encoding="utf-8") as changed:
        paths = [line.strip() for line in changed if line.strip()]
    if not paths:
        print("nothing changed in this pull request")
        return 0

    bad = find_bad_keys(paths, args.translated_ref, args.source_ref)
    if not bad:
        print("no translation needs repair")
        return 0

    for path, keys in sorted(bad.items()):
        print(f"{path}: {', '.join(sorted(keys))}")

    if args.dry_run:
        print(f"would repair {sum(len(keys) for keys in bad.values())} key(s)")
        return 0

    languages = android_language_map(HERE.parent / "crowdin.yml")
    if not args.no_crowdin:
        token = os.environ.get("CROWDIN_TOKEN") or os.environ.get(
            "CROWDIN_PERSONAL_TOKEN"
        )
        if not token:
            raise RepairError(
                "CROWDIN_TOKEN is not set; refusing to repair the branch without first "
                "removing the bad translations from Crowdin"
            )
        for path, keys in sorted(bad.items()):
            remove_from_crowdin(path, keys, languages, token)

    for path, keys in sorted(bad.items()):
        with open(path, encoding="utf-8", newline="") as handle:
            original = handle.read()
        fixed, removed = remove_elements(original, keys)
        missing = keys - set(removed)
        if missing:
            raise RepairError(
                f"{path}: could not find {', '.join(sorted(missing))} in the file"
            )
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(fixed)
        print(f"{path}: removed {', '.join(sorted(set(removed)))}")

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RepairError as error:
        print(f"repair failed: {error}", file=sys.stderr)
        sys.exit(1)
