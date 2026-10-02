# Translations

The app's strings live in [`manager/src/main/res/values/strings.xml`](../manager/src/main/res/values/strings.xml), and
translations come from Crowdin.

- Project: **Shizuku-Next** (id `935085`), source language `en`, mapped by [`crowdin.yml`](../crowdin.yml)
- Two GitHub Actions do the syncing (`.github/workflows/crowdin.yml` and
  `translations-merge.yml`); nothing has to be run locally

## What runs when

| When | What |
| --- | --- |
| A push to `master` that changes `values/strings.xml` | `upload sources` — Crowdin learns what there is to translate |
| Nightly at 03:17, or *Run workflow* | `download translations` into a pull request on the `l10n` branch |
| *Run workflow* with **seed translations** ticked | uploads the repository's own files as translations, for a language Crowdin does not have yet |
| That pull request | merged automatically, squashed, if it only touches `values-*/strings.xml` and they pass the checks below |

The check and the merge happen **in the same run** as the download, not in a workflow of their
own reacting to the pull request: GitHub starts no runs for events caused by `GITHUB_TOKEN`, and
the pull request is opened with that token, so a separate workflow waits for an event that never
arrives. That is why `merge` sits inside `crowdin.yml`, and why `translations-merge.yml` exists
only for pull requests opened by somebody else.

So the loop is: change a string → push → Crowdin has it → a translator writes it →
the next nightly opens a pull request → it merges itself. The two things that remain a
person's are the push and the translating.

The merge is automatic because a translation cannot break anything the way code can, but the
pull request is checked first, and it is left open when:

- it changes any file other than `manager/src/main/res/values-*/strings.xml`
- a file is not valid XML, or holds no strings at all
- a file is **90% or more the English text**, which means it was exported without
  *skip untranslated strings* and is not a translation

Keys that the source no longer has are reported and do not block: every locale carries a few
from strings renamed upstream, Android ignores a translation with nothing to attach it to,
and the next export drops them.

## The two secrets it needs

Both go in *Settings → Secrets and variables → Actions* on the repository, never in the tree:

- `CROWDIN_PERSONAL_TOKEN` — a Crowdin personal access token with *Source files & strings*
  and *Translations* at Read and Write. Rotate it if it has ever been pasted anywhere else.
- `CROWDIN_PROJECT_ID` — not needed: the id is in the workflow, which is not a secret.

The Crowdin project is also *linked* to this repository through its own GitHub integration,
set up before these workflows existed. That integration uploads sources and pushes its own
translation commits to a `crowdin` branch, and opens a pull request for them that nothing was
merging.

**That side cannot be configured through the Crowdin API** - it has no endpoint for it; the
only place the integration's *push sources* / *push translations* / *create pull requests*
toggles live is the project's Integrations page in the Crowdin web interface. The action uses
`l10n` so the two can never write the same branch, and with both untranslated switches on the
integration's own pull request is now a correct one rather than 193 empty language folders, so
leaving it running costs a duplicate pull request and nothing else. To have one path instead of
two, turn *push translations* off there (or suspend the Crowdin GitHub app once these workflows
have run at least once, since the upload half is the Action's job now).

## Only translated strings are exported

Two switches sound like they do the same thing and do not:

| Setting | Meaning | Here |
| --- | --- | --- |
| *skip untranslated strings* | leave untranslated strings **out of** an exported file, so what is written is only what someone translated | **on** - this is what stopped English text being written into languages that have none |
| *skip untranslated files* | **omit files that are not fully translated** | **off** |

That second one is the trap, and it cost a day: *reading* it as "do not write a file for a
language with nothing in it" is natural and wrong. Since no language here is 100% translated,
turning it on made Crowdin export **nothing at all**, and the sync reported success while
finding no changes, run after run - which is what "the pull request is empty" turned out to mean.

With the first switch off instead, Crowdin writes the source text into every language that has no
translation of its own, which is how this repository came to carry ~200 folders that looked
translated and read as English.

**A locale folder here means someone has translated into that language**, not that the whole file
is translated. Files written before the switch was turned on still carry English text for the
untranslated half; the text is what the app would fall back to anyway, so nobody sees anything
wrong, and each export strips a little more of it. The first sync that ran wrote 23 files, added
483 lines and **removed 8,113**: `values-ja` went from 491 strings to 382, `values-fil` from 491
to 115, and the English copies that were never doing anything are gone.

If your language has no folder, or is missing from the system's app-language list, it has not
been translated yet rather than being broken.

## Which folders the export writes

The project's target languages are **regional locales** - Japanese is `ja` whose Android code is
`ja-rJP`, Russian is `ru-BY`, Indonesian's is the legacy `in-rID` - while this app's folders are
named for the plain language. So Android's own placeholder wrote `values-ja-rJP`, `values-ru-rBY`
and `values-in-rID`: 32 folders the app does not carry, against the one (`values-pt-rBR`) that it
does. Neither placeholder alone gets this right - `%two_letters_code%` collapses `pt-BR`, `zh-CN`
and `zh-TW` instead.

[`crowdin.yml`](../crowdin.yml) therefore names each language explicitly in a `languages_mapping`,
and lists the ten languages the app does not carry in `excluded_target_languages` so their
translations stay in Crowdin rather than arriving as folders the app would then advertise. The
check in `ci/check-translations.py` refuses any file that would create a folder the repository has
not got, so an unmapped language cannot slip in by accident.

**To start shipping a language**: map it in `crowdin.yml`, remove it from the excluded list, and
merge its first pull request by hand - the check only accepts folders that already exist, which is
the point of it.

## The ten the licence brought in

German, French, Spanish, Italian, Dutch, Swedish, Turkish, Arabic, Greek and Serbian were excluded
for one reason only - hosted words - and the Open Source licence removed that reason. They are
mapped in [`crowdin.yml`](../crowdin.yml) like the rest now, and they turned out to be the best
translations in the project: about 118 strings each, **none of them the English text**, against
roughly three quarters of the files in the languages that were already here. The app went from 25
languages to **35**.

Two things were needed that are worth knowing about:

1. **The exclusion list is stored on the file in Crowdin, not just in the config.** Removing it
   from `crowdin.yml` changed nothing, because the CLI had set it on the file when it uploaded
   with it, and an upload does not clear it either. It is cleared through the API, which takes a
   **JSON Patch document** for this - `PATCH /projects/<id>/files/<id>` with
   `[{"op":"replace","path":"/excludedTargetLanguages","value":[]}]`. A plain object is refused
   with `jsonPatchInvalid`, the same trap the project settings have.
2. **An empty `excluded_target_languages: []` in the config is rejected as invalid YAML** by the
   CLI, so the key is left out rather than stated as empty.

The first pull request that carried them was refused automatically, which is the guard working:
adding a language to the app is a decision, and the check only accepts folders that already
exist. It was merged by hand, and that is the step every new language takes.

## Which languages ship

The folders that ship are the ones with real content — **37 languages** now, from the Polish, Czech,
Finnish and Norwegian folders at 758 strings down to the tail at about 115, the ten the licence
brought in sitting
at about 118 each. The ~200 that were nothing but English were removed, which also shrank the app:
`resources.arsc` fell from 3.7 MB to 1.1 MB, taking the release APK from 7.2 MB to 4.6 MB. The ten
added since cost about 178 kB of source XML between them, against the thousands of lines of English
copies the first sync deleted.

A language appears by someone deciding it should: its folder has to exist in the repository before
the check that guards the translation pull requests will let a file for it through, which is
recorded above. Once it does, there is nothing to maintain by hand — its folder arrives with the
next sync as soon as it has translations. Deleting a folder removes the language from the system's
app-language list, because the list is generated from the folders (`generateLocaleConfig = true` in
`manager/build.gradle`) rather than from a file in the tree.

## Why the language list is short

Crowdin charges **hosted words, which are the source words multiplied by the number of target
languages**, so an unused language is not free: it re-hosts every string the app has. This project
once had **314** target languages against a source of about 3,411 words - roughly **1,071,000 hosted
words** - and every source update multiplied by all of them. The language list was cut to the ones
with translators behind them, which is where the 33 of today came from.

So adding a language costs about **3,411 hosted words**, one language's worth of the whole app.
Add the ones with translators behind them, and drop a language rather than leave it at 0%: its
translations are held in this repository either way, so nothing ships differently either way.

That ceiling decided how many languages this app could carry until the Open Source licence was
granted, which is what the sections below record.

## Where the account actually stands

Measured against the project as it is now:

| | Languages | Hosted words |
| --- | --- | --- |
| The source | - | 517 strings, **3,411 words** |
| The project | **33** | **112,563** |
| Without the ten that cannot be exported | 23 | 78,453 |
| What the free allowance fits | **17** | 57,987 |

The free plan's published allowance is **60,000 hosted words**, counted across every project on
the account, and the project was roughly at twice it. That is what the figures above describe; it
stopped being a constraint on **30 September 2026**, when the Open Source licence was granted - the
numbers are kept because the charge was never the interesting part. What is now decided by the
language list is only what the app carries.

## The Open Source license

The way off that ceiling is Crowdin's free license for open-source projects: unlimited projects,
strings and members, which makes the language list a decision about the app rather than about a
bill. It is not something the API can apply for - the form is on the website, it wants the project
lead logged in, and it is read by a person:

- form: <https://crowdin.com/product/for-open-source>

Their criteria, and where this project stands:

| Criterion | Here |
| --- | --- |
| A translation project in Crowdin | yes, `935085` |
| An OSI-approved license | Apache-2.0 (see `LICENSE`) |
| Source publicly available | yes |
| No commercial product around it | yes |
| You are the project lead | yes |
| Working on it for at least three months | the continuation of a project that is older than that; the repository itself was created 2026-09-26, so say where it came from rather than leave the date to be guessed at |
| An active community | 30 contributors, PRs from outside |
| News kept up to date | the README, updated with each change |
| Regular releases | tagged releases, most recent on the day this was written |

Submitting the form also agrees to two things worth knowing before it is sent: joining Crowdin's
beta group, and contributing this project's translations to Crowdin's global translation memory
in exchange for access to their machine translation.

### What the licence was used for

The ten languages it unblocked are shipped - see "The ten the licence brought in" above - which
leaves one piece of housekeeping:

**`zh-CN` and `zh-TW` were not target languages at all**, while the app ships `values-zh-rCN` and
`values-zh-rTW` - two folders nothing could ever update. They are in the project now, and seeded
from the only copy of those translations that existed, which was this repository.

That took three steps, in this order:

1. **Add the languages** - `PATCH /projects/935085` with
   `[{"op":"replace","path":"/targetLanguageIds","value":[...]}]`, the project's list plus the two.
2. **Seed them from the repository** - a workflow run with `seed_translations` ticked, which is the
   ordinary upload step with `upload_translations` turned on: the CLI reads back the files its
   `translation` pattern names, so `values-zh-rCN/strings.xml` becomes zh-CN's translations and
   `values-zh-rTW/strings.xml` becomes zh-TW's. 119 strings each.
3. **Let the sync take over** - the next download updated both folders and merged itself, because
   the folders already existed and the files were real translations.

The order matters, and so does the timing: adding a language and leaving it empty does not work,
because an untranslated language exports a file with no strings in it and the check refuses that -
for a folder that already exists, an empty file would be a wipe. And a seeding run uploads the
repository's copy of *every* language, not just the new one, so it belongs right after a sync:
run it when Crowdin has moved on and it would push this repository's older text back over a
translator's work.

If the licence had not been granted, the fallback was the arithmetic in the section above: 17
languages at 3,411 words each is 57,987 and 18 is 61,398, so eight of the 23 would have had to go.

## The six the exclusion was holding back

Thirteen of the project's 48 target languages sat at **exactly 0%**, and the cause was not that
nobody had got round to them. They were the same thirteen languages `excluded_target_languages`
names in [`crowdin.yml`](../crowdin.yml), and that setting means what it says: the file is *not
available for translation* into those languages. A translator opening the project for Hindi found
no file at all. Five translators are on the project with access to every language, and six
languages had nothing for them to open.

The two lists being in step is what made it look deliberate: 35 mapped and shipping, 13 excluded
and silent, and 35 + 13 is the project's 48. Nothing was out of sync; the list was simply doing
what it said.

Reading progress per language exposed the exclusion biting in a way nobody would guess from the
totals - `preTranslateAppliedTo: 0` on all thirteen. AI pre-translation **had** already been run
over exactly those languages, twice, and applied nothing, because a file excluded from a language
has no target strings to fill. Both runs reported success. That is most of why the gap survived
being looked at.

Six of the thirteen are languages the app is genuinely missing, and all six were opened:

| opened | folder | was |
| --- | --- | --- |
| `hi` | `values-hi` | Hindi, never opened |
| `bn` | `values-bn` | Bengali, never opened |
| `te` | `values-te` | Telugu, never opened |
| `mr` | `values-mr` | Marathi, never opened |
| `pa-IN` | `values-pa` | Punjabi, never opened |
| `ur-IN` | `values-ur` | Urdu, never opened |

Six stay shut, and not one of them is a language this app is missing: `ru-MD` against the `ru` that
now ships as `values-ru`, `tr` against `tr-CY`, `es-ES` and `es-US` against `es-419`, `bn-IN` as
Bengali a second time beside `bn`, and `en-IN`, which is English, the source. Opening a duplicate is
the one edit to avoid here: they are unmapped, so the CLI falls back to Android's own placeholder,
and `ru-MD` resolves to `values-ru-rRU` rather than the `values-ru` this app carries. Two project
languages, one file, last export wins.

`ru` was the seventh, and it turned out not to be a duplicate at all - it is the target that now
fills `values-ru`, with `ru-BY` moved off to `values-ru-rBY`. That was issue #31, and it is the one
case where opening a language meant moving a folder rather than adding one, so it is recorded in
its own section below.

**No skeleton could be given, and none of the two mechanisms that fill strings automatically
reaches these six:**

| method | outcome |
| --- | --- |
| `mt` - Crowdin Translate, engine `879831` | works, and filled `pl`, `cs`, `fi`, `ja`, `no`, `pt-BR`, `ro`, `ru-BY`, `tr-CY`, `uk` and `zh-CN` - but it supports 24 languages, and Hindi, Bengali, Telugu, Marathi, Punjabi and Urdu are not among them |
| `ai` - `POST /projects/935085/pre-translations`, `method: "ai"` | accepted with `202`, runs to `finished` in under a minute, applies **0** strings. Three times, the last of them with the project's prompt configured |

The prompt was the first suspect and turned out not to be the cause.
`/projects/935085/ai/settings` had `preTranslationAiPromptId: null`, and that field **is** writable
through the API - a JSON Patch on the same endpoint, `[{"op":"replace","path":"/preTranslationAiPromptId","value":704135}]`,
where `704135` is the prompt the project's own earlier runs used. It is validated, too: `999999999`
and `1` are both refused with `notInArray`, so the value is a real prompt of this account and not a
number Crowdin was going to ignore. The setting now holds `704135`, and the third run applied
nothing anyway, in about the time it takes to look at 787 strings rather than translate them.

So what is left is the provider behind the prompt, which lives at the organisation level and has no
API of its own - `/ai/providers` and `/projects/935085/ai/providers` are both `404`. Connecting one
is a web-interface step, and until it is done `method: "ai"` accepts the request, reports success
and writes nothing, which is the failure being reported on Crowdin's own forum as *"pre-translation
via AI skips all the strings"*.

A third route exists and is a decision rather than a step: an MT engine that does cover them,
such as Google Cloud Translation, added to the project with an account of its own. Crowdin
Translate cannot, whatever is configured around it.

So the six start where the other 35 did - an empty target field beside each of the 787 source
strings, which is how every language here began, the tail ones sitting at 114 to 125 strings. What
they needed was the door, not a head start.

Two consequences for whoever picks one up. Its folder still has to exist in the repository before
a translation file for it will merge, and the languages are mapped now, so the first nightly after
somebody translates a string opens a pull request the check refuses: that one is merged **by
hand**, as above. And until then nothing about the language is live - a language with no
translations exports no file at all, which is why mapping all six costs nothing while they are
empty.

## The Russian that arrived as resource ids

Two issues opened the same morning turned out to be one problem seen from two sides. **#31** was a
Russian speaker who opened <https://crowdin.com/project/Shizuku-Next/ru> and found no `strings.xml`
at all. **#29** was a contributor attaching a "Russian full translate for v14.0.9". Both come down
to the same thing: this app's Russian ships from `values-ru`, and the target that had been writing
that folder was `ru-BY`, "Russian, Belarus" - the plain `ru` target, the one the website sends a
Russian speaker to, was excluded from the file and therefore empty.

### The attached file is valid, and that is the problem

It parses, it holds 881 `<string>` elements, it is UTF-8 without a BOM - and every name in it is a
resource id, `string_7f100001` through `string_7f100405`. `0x7f` is this app's package and `0x10`
is the string type, so it is a dump of a release APK's `resources.arsc`, with the names erased
because `minifyEnabled` and `shrinkResources` are on for release in `manager/build.gradle`.

As sent it matches **0 of the 789** keys in `manager/src/main/res/values/strings.xml`, so it would
have translated nothing. [`ci/check-translations.py`](../ci/check-translations.py) would have
accepted it - valid XML, more than twenty strings, nowhere near 90% English, and `values-ru`
exists - and merged a file that changes no string in the app. That is the failure the check exists
to catch and cannot, because every rule it applies is about the shape of a file rather than whether
its keys are ones the app has.

### Recovering it

Resource ids are assigned in alphabetical order within a type, which is the trap: one string added
anywhere in `values/strings.xml` shifts every id after it. Mapping the file's ids against the
current source gives 36 placeholder mismatches and text such as `intents_regenerate` rendering as
"sans-serif". They line up only against a build from the same tag, and the release for
**v14.0.9-next** is published beside a debug APK built from the same commit, whose names survive.
Going through that dump resolves all 881 entries, **718** of them this app's own strings (the rest
are `abc_`/`androidx` library keys), with **0 placeholder mismatches**.

The values need care for the same reason: `resources.arsc` holds them cooked. `Can\'t` is stored
as `Can't`, `\n` as a real newline, and compiled markup as real child elements - so reading them
with `ElementTree`'s `.text` stops at the first child, and all thirteen strings containing bold
lost their tail. `home_usb_adb_needs_network` ended at "Подключитесь к Wi-Fi один раз или выберите ".
Reading the raw inner XML keeps them: a source written as `<![CDATA[...]]>` keeps its markup, and
one written with real tags has them stripped, which is what `getString()` returns for it anyway.

### Seeding the target

`POST /projects/935085/translations` is the documented way in and does not work for this. It
accepts the upload - `201`, every time - and imports about one string per run, leaving progress at
0%. The CLI works, in the configuration the workflow already uses:

```bash
crowdin upload translations -l ru -c crowdin.yml
```

That imported **708** strings in one run. One wrinkle is worth knowing: Crowdin's own Android code
for `ru` is `ru-rRU`, so a seeding config needs `ru: ru` in `languages_mapping` to find `values-ru`
- and is better off dropping the other 41 mappings, which would let a seeding run read one
language's folder and write it into another's.

The split, in the end:

| target | folder | strings |
| --- | --- | --- |
| `ru` | `values-ru` | 707 - the folder the app's Russian ships from |
| `ru-BY` | `values-ru-rBY` | 496, unchanged - moved rather than retranslated |

`values-ru` went from 496 strings to 707, and the export of it is purely additive against what was
committed: no string that was there before was dropped or retranslated. `values-ru-rBY` is
line-for-line what `values-ru` used to hold, so the Belarus variant lost nothing in the move, and
`ru` stays out of `excluded_target_languages`, which leaves five there against 43 mapped.

## The same bug in Turkish

Issue **#25** reported the Turkish translations as "somewhat unnatural or machine-translated" -
*bekçi köpeği*, literally "watchdog dog", for `device_watchdog` - and asked for Turkish and Turkish
(Cyprus) to be separated. It was closed, and then reopened in effect by its own last comment: the
reporter had translated into `tr-CY`, the folder the app shipped as `values-tr`, and could not
download it, while the plain `tr` target "doesn't seem to have any strings.xml". That is issue #31
in Turkish, and it is the same cause.

So the same fix was applied, and the pair is worth recording as a pattern rather than a one-off.
Both were a regional target - `ru-BY`, `tr-CY` - writing the folder the language actually ships
from, while the plain target sat in `excluded_target_languages` with nothing to translate:

| target | folder | before | after |
| --- | --- | --- | --- |
| `tr` | `values-tr` | excluded, 0 strings | seeded from the repository, 593 of 787 (83%) |
| `tr-CY` | `values-tr-rCY` | wrote `values-tr`, 619 | moved, 618 - unchanged |

Both were seeded with the CLI, not the API, for the reason in the section above, and `tr` needed
the same `tr: tr` entry in `languages_mapping` that `ru` did - Crowdin's own Android code is
`tr-rTR`. The download then shrank `values-tr` by 26 strings, and every one of them was text
identical to the English source (`adb`, `Root`, `Port`, `A-Z`): untranslated placeholders that the
project's `skipUntranslatedStrings` is supposed to leave out and had been exporting. Nothing
translated was lost, and the folder now holds 592 real Turkish strings against `tr-CY`'s 618.

One consequence is worth stating plainly, because it is the half of #25 that a folder split does
not fix: **`tr-CY` moved to `values-tr-rCY`, which this app has never shipped, so on Android it now
matches no device.** A Turkish speaker still gets `values-tr`, which at 592 strings is smaller than
the `tr-CY` content the folder used to hold, and the two hold different translations of the same
strings. The direction that fixes both is the one the reporter asked for - make `tr` the target
that fills `values-tr`, and merge or retire `tr-CY` rather than shipping it beside. The same
question sits over the Russian pair, and it is the maintainer's to answer, not a sync's.

## Checking progress without the web interface

With a Crowdin personal access token in `CROWDIN_TOKEN` (Account → API tokens; the one for this
project is held by the maintainer), progress can be read straight from the API:

```bash
CROWDIN_TOKEN=… python3 - <<'EOF'
import json, os, urllib.request
base = "https://api.crowdin.com/api/v2"
headers = {"Authorization": "Bearer " + os.environ["CROWDIN_TOKEN"], "Accept": "application/json"}
def get(path):
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=60) as r:
        return json.load(r)
rows = [i["data"] for i in get("/projects/935085/languages/progress?limit=500")["data"]]
for row in sorted(rows, key=lambda r: -r["translationProgress"])[:20]:
    print(f'{row["languageId"]:10} {row["translationProgress"]:3}%')
EOF
```

At the time of writing every one of the project's 33 languages had a translated string, and the
other 281 were the empty ones that were removed — much of the project's
language list is regional duplicates (`de-BE`, `fr-LU`, `nl-SR`) from when the project was set up,
and Hindi, Arabic, German, French, Russian and Spanish are among the ones with nothing, so their
folders are gone rather than shipping as English.
