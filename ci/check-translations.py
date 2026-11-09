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
repository does not have, is not valid XML, holds no strings at all, holds an apostrophe the
resource compiler refuses (see `translation_escapes.py`, which is the same rule and the
script that repairs one), or is 90% or more the English text - the last one being how a
language that was never translated gets exported looking translated. It also refuses a
translation whose format placeholders differ from the source, whose ADB command does not
preserve the source command's executable, subcommand and arguments, or that empties a value
that was non-empty on master. A new empty translation is refused too. Keys the source no
longer has are reported and do not block: every locale carries a few from strings renamed
upstream, Android ignores a translation with nothing to attach it to, and the next export
drops them.

The apostrophe rule is here for the pull request a person opens. The ones the download
writes are repaired before this ever sees them, so a refusal here means somebody handed in
translations by hand and one of them would not compile.

The folder rule is the one that earns its keep. Which languages ship is decided here, in
the repository, and Crowdin's own list of target languages is not the same list: it holds
regional locales (`ja`, `fi`, `ru-BY`) whose names do not map to ours, and an export of
those once arrived as 193 new folders for languages with no translations at all.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter

# The apostrophe rule lives in the script that repairs it - this file is run as
# `python3 ci/check-translations.py`, so the directory holding it is already the first entry
# on the path.
from translation_escapes import bare_apostrophes

# One or more qualifiers, so `values-ja`, `values-pt-rBR` and `values-sr-rCyrl-rME` all
# count as locale folders. Whether we want the folder is a separate question, below.
LOCALE_PATH = re.compile(r"^manager/src/main/res/values(-[A-Za-z0-9+]+)+/strings\.xml$")

# Above what a real translation looks like here (Japanese is 74% identical to the source
# because it still carries the English text of everything untranslated) and below the
# ~100% a file that was never translated at all comes out at.
SAME_AS_SOURCE = 0.9
SMALLEST_FILE = 20

# Android format placeholders. `%%` is a literal percent sign and is not a placeholder.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# Commands in the source are meant to be copied. The surrounding sentence is translated,
# but the executable, subcommand and arguments have to survive unchanged.
ADB_COMMAND_START = re.compile(
    r"^adb\s+(?:"
    r"shell\s+(?:pm|settings|appops|ps|am|cmd|dumpsys|input|getprop|setprop|svc|wm|logcat|id|sh)\b"
    r"|tcpip\b|pair\b|connect\b|disconnect\b|devices\b|install\b|uninstall\b"
    r"|push\b|pull\b|forward\b|reverse\b|start-server\b|kill-server\b|wait-for-device\b"
    r"|root\b|unroot\b|remount\b|reboot\b|sideload\b|bugreport\b"
    r")"
)
ADB_COMMAND_CANDIDATE = re.compile(r"(?:^|\\n|[^\w\\])(adb\s+[^\n\\<]+)")


def show(rev, path):
    return subprocess.check_output(["git", "show", f"{rev}:{path}"])


def strings_at(rev, path):
    root = ElementTree.fromstring(show(rev, path))
    return {element.get("name"): (element.text or "") for element in root.findall("string")}


def full_text(element):
    return "".join(element.itertext())


def texts_at(rev, path):
    root = ElementTree.fromstring(show(rev, path))
    texts = {
        element.get("name"): full_text(element)
        for element in root.findall("string")
    }
    for plural in root.findall("plurals"):
        name = plural.get("name")
        for item in plural.findall("item"):
            texts[f"{name}[{item.get('quantity')}]"] = full_text(item)
    return texts


def commands_at(rev, path):
    root = ElementTree.fromstring(show(rev, path))
    commands = {}
    for element in root.findall("string"):
        found = []

        def add(value):
            value = " ".join(value.strip().rstrip(".,;:!?").split())
            if ADB_COMMAND_START.match(value) and value not in found:
                found.append(value)

        has_code = False
        for child in element.iter():
            if child.tag in ("b", "code"):
                has_code = True
                add(full_text(child))
        if not has_code:
            for match in ADB_COMMAND_CANDIDATE.finditer(full_text(element)):
                add(match.group(1))
        if found:
            commands[element.get("name")] = found
    return commands


def format_placeholders(text):
    return Counter(
        placeholder
        for placeholder in PLACEHOLDER.findall(text)
        if placeholder != "%%"
    )


def describe_placeholders(placeholders):
    if not placeholders:
        return "none"
    return ", ".join(
        f"{count} x {placeholder}"
        for placeholder, count in sorted(placeholders.items())
    )


def normalize_text(text):
    return " ".join(text.split())


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
    source_texts = texts_at(source_ref, "manager/src/main/res/values/strings.xml")
    source_commands = commands_at(source_ref, "manager/src/main/res/values/strings.xml")
    print(f"source has {len(source)} strings")
    if source_commands:
        print(f"source has {len(source_commands)} string(s) with ADB commands")

    for path in paths:
        try:
            translated = strings_at(translated_ref, path)
        except ElementTree.ParseError as error:
            problems.append(f"{path}: not valid XML ({error})")
            continue

        if not translated:
            problems.append(f"{path}: no strings in it")
            continue

        unescaped = sorted(
            key for key, value in translated.items() if bare_apostrophes(value)
        )
        if unescaped:
            problems.append(
                f"{path}: {len(unescaped)} value(s) hold an apostrophe the resource"
                f" compiler refuses, starting at {unescaped[0]}"
                f" - python3 ci/translation_escapes.py escapes them"
            )
            continue

        translated_texts = texts_at(translated_ref, path)
        try:
            base_texts = texts_at(source_ref, path)
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            base_texts = {}

        for key, source_value in source_texts.items():
            translated_value = translated_texts.get(key)
            if translated_value is None:
                continue
            source_placeholders = format_placeholders(source_value)
            translated_placeholders = format_placeholders(translated_value)
            if source_placeholders != translated_placeholders:
                problems.append(
                    f"{path}: {key}: format placeholders differ from the source"
                    f" (source: {describe_placeholders(source_placeholders)};"
                    f" translation: {describe_placeholders(translated_placeholders)})"
                )

            # An empty value that was already empty on master is left alone: those are old
            # gaps, not something this pull request introduced. A value this pull request
            # emptied (or a new empty value) is refused, so a missing translation cannot
            # silently ship as a blank label.
            if (
                source_value.strip()
                and not translated_value.strip()
                and (key not in base_texts or base_texts[key].strip())
            ):
                problems.append(f"{path}: {key}: translation is empty")

        for key, commands in source_commands.items():
            translated_value = translated_texts.get(key)
            if translated_value is None:
                continue
            normalized_translation = normalize_text(translated_value)
            missing = [
                command
                for command in commands
                if command not in normalized_translation
            ]
            if missing:
                problems.append(
                    f"{path}: {key}: ADB command is not preserved"
                    f" (expected {missing[0]!r})"
                )

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
