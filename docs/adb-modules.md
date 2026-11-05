# Writing an ADB module for Shizuku-Next

An ADB module adds to Shizuku: a zip with a `module.prop` in it, whose scripts run through the
Shizuku server with the environment the ADB module format defines, and whose optional WebUI is a page
inside this app with a bridge back into that shell.

This app implements that format rather than one of its own. If you have written a module for Shevery
or Xhizuku, it installs and runs here unchanged. The last section lists the few places where this
implementation answers differently, so nothing is a surprise.

The format is [kerneldroid](https://github.com/kerneldroid)'s
[Nightzuku](https://github.com/kerneldroid/Nightzuku), which is where the modules, the actions and
services and the `window.Shizuku` bridge come from;
[Shevery](https://github.com/HmnDev-Tech/shevery) publishes the guide and API reference this one is
written against, and [Xhizuku](https://github.com/xeonleonreal/Xhizuku) implements the same format.

## The package

A module is a zip with `module.prop` at its root. Everything else is optional:

```text
my-module.zip
├── module.prop
├── banner.png          (or banner.jpg / banner.jpeg / banner.webp)
├── action.sh
├── service.sh
└── webui/
    └── index.html
```

## module.prop

```properties
id=my-module
name=My Module
version=1.0
versionCode=1
author=Your Name
description=What this module does, in a sentence.
banner=banner.png
webui=webui
action=action.sh
usesShellBridge=true
```

| Key | Required | What it means |
| --- | --- | --- |
| `id` | yes | The directory name the module is installed under. `^[A-Za-z][A-Za-z0-9._-]{1,63}$` - a letter, then letters, digits, dots, underscores or hyphens. |
| `name` | yes | What the card is called. |
| `version` | no | Shown on the card as it is written. |
| `versionCode` | no | The number that decides whether an update manifest is newer. |
| `author` | no | Shown on the card. |
| `description` | no | Shown on the card, and matched by the search box. |
| `banner` | no | A picture named by the module. Without it, `banner.png`, `banner.jpg`, `banner.jpeg` and `banner.webp` are tried in that order. |
| `webui` | no | The WebUI directory. Without it, `webroot`, `webui` and `web` are tried in that order. The page is only offered when `<that directory>/index.html` is a file. |
| `action` | no | The script an action runs, relative to the module directory. Defaults to `action.sh`. |
| `usesShellBridge` | no | `true` asks for the WebUI's shell bridge. A module that does not ask for it gets the module's own information and nothing else. |

`service.sh` is not named here: a file at the module root with that name is the service.

Values are read literally. This is deliberately not a properties parser, so a description holding a
`:` or a backslash arrives as written.

## Installing

The zip is unpacked into a staging directory and only renamed to the module's `id` once its
`module.prop` has named it, so a refused zip leaves nothing that looks installed, and a module can
never choose where it lands. Installing over a module that is already there replaces it, which is
what installing a newer zip means.

Checked while reading, before anything is written:

- every entry is a relative path that stays inside the module: no leading `/`, no `..` anywhere, no
  drive letters, no NUL bytes;
- at most **2048 entries**;
- at most **200 MB** unpacked in total;
- at most **256 KB** per `.sh` file.

Modules live in the app's external files directory, under `adb_modules/<id>`. Not its private
storage, which is the obvious place and does not work: the shell Shizuku runs a script as cannot
enter an app's own directory, so the first `cd` into a module would fail and the module would look
broken for no visible reason. On a device with no external storage at all, the private directory is
used instead, and a module can be installed and read there but its scripts cannot run.

## Running

A script is run as one command line, which is the whole of the contract:

```sh
cd '<module directory>' && \
  MODDIR='<module directory>' \
  ASH_STANDALONE=1 \
  SHIZUKU_MODULE_ID='<id>' \
  SHIZUKU_MODULE_MODE='safe|full' \
  SHIZUKU_MODULE_TRUSTED='0|1' \
  SHIZUKU_MODULE_BACKGROUND='0|1' \
  sh '<script>'
```

The working directory is the module directory, so `$MODDIR` is what to use for anything the module
ships, and a bare file name works as well.

`ASH_STANDALONE=1` is the one that is not obvious: modules are written for Magisk's environment,
where `/system/bin/sh` is busybox ash, and a module that says `ash -c` or reaches for a busybox
builtin wants that shell to be standalone rather than looking for its own files somewhere it will
not find them.

Limits while a script runs:

| Limit | Value |
| --- | --- |
| Clock | **120 seconds**. The process is killed when it runs out, and the exit code is **124**, which is what `timeout` returns. |
| Output kept | **64 KB per stream**. The first 64 KB are kept here; the format's other implementations keep the last. |
| Output drained | Past the cap the stream is still read and dropped, so a script that prints more than 64 KB finishes instead of blocking on a full pipe. |

What a run printed is written to the module's own log, next to it:

```text
<module directory>/logs/action-last.log
<module directory>/logs/service-last.log
```

A run that printed nothing writes `No output.`, so an empty log is a fact about the script rather
than a log that was never written.

## Actions

`action.sh` is what the card's Action button opens. It runs on its own screen the moment it opens -
that screen exists to watch something - and its output appears as it is printed rather than at the
end. The bar's button stops a run that will not end by itself, and the log above keeps whatever was
printed by then.

Unless the module has been trusted (below), the command is shown for approval first. An action is
always allowed to run; what may not run is a command nobody has seen.

## Services

`service.sh` is the part of a module that runs with nobody watching it, so it has its own gate. It
starts only when all of these hold:

- the module is switched on;
- access is **Full access** (the tuning icon on the modules screen);
- **Allow background actions** is on;
- the user approves the command, unless the module is trusted.

One service per module is tracked at a time, its output goes to the service log rather than to a
screen, and the card's service button says Stop while it is running - which is asked of the
processes, not remembered, so a service started earlier still shows as running.

## WebUI

A module's page is served from its own directory inside this app's WebView. It is opened from the
card, it takes the whole screen, and the platform's back gesture goes back through the page's own
history before leaving it.

The WebView is configured for an offline page:

- JavaScript is on;
- **there is no network**: `blockNetworkLoads` is set, so a page cannot reach the internet at all;
- files next to the page can be read, including with `fetch()` (`allowFileAccess` and
  `allowFileAccessFromFileURLs`), because a page that ships its own data has to be able to read it;
- `content://` URLs are not readable;
- `localStorage` and `sessionStorage` work;
- the platform is allowed to darken a light page when the app is dark.

### The bridge

`window.Shizuku` is the whole of what the page can reach.

```javascript
const info = JSON.parse(window.Shizuku.getModuleInfo());
// { ok, id, name, version, versionCode, author, description, enabled, accessMode,
//   trusted, moduleDir, webRoot, background, shellBridge,
//   permissions: { action, service, webBridge, webNetwork, webDownload, declaresShellBridge } }
```

Both shapes are there on purpose: the flat fields are the ones this format's published API
documents, and `permissions` is what the pages written for its other implementations read -
`permissions.webBridge` is the one that decides whether a page shows its bridge as available.
`webNetwork` and `webDownload` are `false` and always will be: a page here has no network at all, so
a page told the truth about it can fall back instead of waiting for a download that never comes.

```javascript
const result = JSON.parse(window.Shizuku.exec("id"));
// { ok, exitCode, stdout, stderr, timedOut }
```

`ok` is `true` when the command exited zero and did not run out of time.

```javascript
const result = JSON.parse(window.Shizuku.execWithOptions("cat file", JSON.stringify({
  timeoutSeconds: 30
})));
```

`exec()` gives a command the same 120 seconds a script gets. `timeoutSeconds` is honoured and
clamped to **1..600** seconds; a timeout that is not a whole number, and options that are not
readable JSON, are refused by name rather than rounded or ignored.

**`stdin`, `cwd`, `env`, `envVars` and `workingDirectory` are refused by name.** Each would be a way
to make the command something other than what the user approved, and a bridge that quietly ignores
an option is worse than one that says it cannot. A command runs as it is written: there is no
working directory set for it and no environment beyond the shell's own, so use an absolute path or
the `moduleDir` from `getModuleInfo()`.

```javascript
window.Shizuku.openSettings();
// { ok: true }
```

This app's module settings hold the two switches a page most often has to send a person to - Full
access and Allow background actions - so the page can send them straight there. It is navigation,
not a command: nothing runs, so nothing is reviewed, and it is not behind the gate below - the page
that most needs the person at these settings is the one whose module cannot run anything yet. The
page closes for it; the module list arrives with the settings dialog already open on it, and the
switches it flips take effect the next time the page asks for anything.

### The gate

Shell methods answer only when all of these hold:

- the module is switched on;
- its `module.prop` says `usesShellBridge=true`, **or the module is trusted**;
- access is **Full access**.

A page that is not allowed still gets `getModuleInfo()`; calls to `exec` answer with
`ok: false`, `exitCode: -1` and the reason in `stderr`.

## Trust

A module can be trusted, which means its commands run without being shown first. Long-press its card
on the modules screen: the action row gains **Trust**, and a trusted module carries a **Full Trust**
chip. Long-press again to hide the action.

Trust covers the review: an action, a service and a page's commands all skip it. It also stands in
for a module that did not ask for the WebUI bridge in its `module.prop` - the same rule the format's
other implementations use, and the one their pages check, so a trusted module's page is told its
bridge is there. Everything else still applies: the module is still switched off with its switch,
output is still capped, the clock still stops a run, and the bridge still needs **Full access** -
trust replaces the module's own request for it, not the access mode. `SHIZUKU_MODULE_TRUSTED` tells a
script which state it is in.

## Switching on and off

A module is switched off by a file named `disable` in its directory, and switched on by its absence -
Magisk's convention, which this format inherits, so a module switched off by hand is switched off.
A module that is off runs nothing: its action and its service are refused, and its WebUI's bridge
answers that it has no shell bridge. Deleting a module removes its directory, logs included.

## Offering an update

A module that wants to be updatable ships a file named `update.json` whose contents are the URL of a
manifest:

```json
{
  "version": "1.1",
  "versionCode": 2,
  "zipUrl": "https://example.com/my-module-1.1.zip",
  "changelog": "What changed."
}
```

The card says an update is available when `versionCode` is above the installed one and there is a
`zipUrl` to install. The zip it points at goes through the same checks as one installed by hand.
This manifest is this app's own convention rather than part of the shared format, so a module without
one simply has no update check here.

## Getting listed

The modules screen's cloud icon finds modules the way the format's other implementations do: a
GitHub search for the topic an author puts on their repository. Any of **`shevery-modules`**,
**`shizuku-modules`** or **`shizuku-next-modules`** works - they are the same set of modules, tagged
for whichever implementation the author wrote against - and a repository carrying more than one of
them is still listed once.

A topic is a claim anyone can make, so every repository found is checked before it is offered: it
has to answer with a `module.prop` at its **root**, read by the same parser the installer uses (an
`id` that could be a directory name, and a name to show). A repository that keeps its module in a
subdirectory, or that only mentions the topic in its description, is not a module and is not listed.

The card's install takes the newest release and installs the **first asset ending in `.zip`**, so
attach one zip to the release (a repository with several zips, or none, will not install as
expected).

The **More apps** list under Labs is a different thing: it is a directory of apps that use Shizuku,
not of modules.

To test a module by hand, the button on the modules screen installs any zip from the file picker.

## Where this implementation answers differently

Everything above works as the format documents it. These are the differences worth knowing:

| Thing | Here | In Shevery |
| --- | --- | --- |
| `stdin`, `cwd`, `env` in `execWithOptions` | refused by name | supported |
| Output past the cap | first 64 KB kept, rest drained | last 64 KB kept, drained |
| WebUI `window.Shizuku.download(...)` | not provided | provided |
| WebUI `window.Shizuku.openSettings()` | provided - opens the module settings | not part of the format |
| ReCommand, command suggestions, AI checker | not provided | provided |
| Full Trust and the shell bridge | Full access plus either `usesShellBridge=true` or trust | the same rule |
| WebUI internet, WebUI download gates | not applicable - a page has no network at all | trust bypasses them |
| `update.json` | this app's own convention | not part of the format |
| Module catalogue | GitHub topic search, newest release's first zip | topic search, with per-author limits |
