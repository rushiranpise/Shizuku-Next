#!/usr/bin/env python3
"""Decide whether a translations pull request is safe to merge without a person.

Two workflows call this: the one that opens the pull request itself, and the one that
reacts to somebody else opening one from the same branch. It is a script rather than a
step in either of them because the two must agree, and a rule kept in two places stops
being one rule.

    check-translations.py <changed-list> <translated-ref> <source-ref>

`changed-list` is a file of paths, one per line, as `gh pr diff --name-only` prints them.
`translated-ref` is the revision holding the translations to check (the branch the pull
request came from), and `source-ref` the revision holding the English strings.

A file is refused when it is not a locale file, would create a locale folder the
repository does not have, is not valid XML, holds no strings at all, or is 90% or more the
English text - the last one being how a language that was never translated gets exported
looking translated. Keys the source no longer has are reported and do not block: every
locale carries a few from strings renamed upstream, Android ignores a translation with
nothing to attach it to, and the next export drops them.

The folder rule is the one that earns its keep. Which languages ship is decided here, in
the repository, and Crowdin's own list of target languages is not the same list: it holds
regional locales (`ja`, `fi`, `ru-BY`) whose names do not map to ours, and an export of
those once arrived as 193 new folders for languages with no translations at all.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

# One or more qualifiers, so `values-ja`, `values-pt-rBR` and `values-sr-rCyrl-rME` all
# count as locale folders. Whether we want the folder is a separate question, below.
LOCALE_PATH = re.compile(r"^manager/src/main/res/values(-[A-Za-z0-9+]+)+/strings\.xml$")

# Above what a real translation looks like here (Japanese is 74% identical to the source
# because it still carries the English text of everything untranslated) and below the
# ~100% a file that was never translated at all comes out at.
SAME_AS_SOURCE = 0.9
SMALLEST_FILE = 20


def show(rev, path):
    return subprocess.check_output(["git", "show", f"{rev}:{path}"])


def strings_at(rev, path):
    root = ElementTree.fromstring(show(rev, path))
    return {element.get("name"): (element.text or "") for element in root.findall("string")}


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2

    changed_list, translated_ref, source_ref = sys.argv[1:]

    with open(changed_list, encoding="utf-8") as changed:
        paths = [line.strip() for line in changed if line.strip()]

    if not paths:
        print("nothing changed in this pull request")
        return 0

    problems = [
        f"{path}: not a translation file"
        for path in paths
        if not LOCALE_PATH.match(path)
    ]

    # Deciding what ships is not this script's job, but adding a language to the app by
    # accident is not the export's either, so a folder has to be one the repository already
    # carries before a translation for it is merged.
    for path in paths:
        if not LOCALE_PATH.match(path):
            continue
        folder = path.rsplit("/", 1)[0]
        if subprocess.run(
            ["git", "cat-file", "-e", f"{source_ref}:{folder}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ).returncode != 0:
            problems.append(f"{path}: would create a locale folder the repository has not got")

    if problems:
        print("\n".join(problems))
        return 1

    source = strings_at(source_ref, "manager/src/main/res/values/strings.xml")
    print(f"source has {len(source)} strings")

    for path in paths:
        try:
            translated = strings_at(translated_ref, path)
        except ElementTree.ParseError as error:
            problems.append(f"{path}: not valid XML ({error})")
            continue

        if not translated:
            problems.append(f"{path}: no strings in it")
            continue

        shared = [key for key in translated if key in source]
        same = sum(1 for key in shared if translated[key] == source[key])
        ratio = same / len(shared) if shared else 0
        stale = len(translated) - len(shared)
        print(
            f"{path}: {len(translated)} strings, {ratio:.0%} identical to the source,"
            f" {stale} no longer in the source"
        )

        if len(shared) >= SMALLEST_FILE and ratio >= SAME_AS_SOURCE:
            problems.append(
                f"{path}: {ratio:.0%} of it is the English text, so it is not a translation"
            )

    if problems:
        print("\n" + "\n".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
