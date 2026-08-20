<img src="docs/media/icon.svg" width="96" align="right" alt="">

# Doppel

**An unofficial Android AI bridge for WhatsApp.**

**[Download the latest APK](https://github.com/totec448-spec/doppel/releases/latest)**
— arm64-v8a, Android 9 or newer, about 26 MB. Every release is signed with the
same key and ships a SHA-256 file next to it.

An Android app that links to WhatsApp as a device on your own second number and
answers messages through supported OpenRouter models. It runs entirely on the
phone. There is no server, VPS, companion process, official WhatsApp Business
API, or PC that must stay online.

The point of the project is not that a model can reply. The point is that the
account reads like a person. It takes time to notice a message. It reads before
it types. It types for as long as the text would take. It goes quiet at night,
comes back in the morning, reacts to things, sends a voice note now and then,
and remembers what you told it three weeks ago.

> [!WARNING]
> `whatsmeow` is an unofficial linked-device client. WhatsApp can rate-limit,
> restrict, or ban an account that uses one. This app has a lot of machinery
> aimed at not looking like a bot. None of it is a guarantee. Use a spare number
> that you can afford to lose, never your main one.

## What you need

- An arm64-v8a Android phone on Android 9 or newer. The bundled native library
  does not currently include x86, x86_64, or 32-bit ARM builds.
- A second WhatsApp number. The app pairs as a linked device, so the number has
  to be active on some phone, which can be the same one.
- An [OpenRouter](https://openrouter.ai) API key with credit on it. Replies,
  media analysis, image generation, TTS, transcription fallback, and the
  optional verifier can each incur provider charges.

Current Android identity: application ID and namespace `de.totec.doppel`,
version `1.0.0` (`versionCode` 1), minSdk 28, targetSdk 36, compileSdk 37.

## Install

Signed APKs are published on the [GitHub Releases page](https://github.com/totec448-spec/doppel/releases).
Each one ships with a `.sha256` file; check it against the APK you downloaded
before installing, and do not install an APK from an unrelated mirror. Android
will ask you to allow installs from the source app you used to open the APK.

The APK is signed with a key that is not in this repository. An update only
installs over an existing copy if it carries the same signature, which is what
keeps your settings and your WhatsApp link across versions.

## Setup

First launch walks you through it in one screen.

1. Type the phone number of the account the bot should run as.
2. Press send code. An eight-character pairing code appears.
3. On the phone that owns that number, open WhatsApp, then the three-dot menu,
   then Linked devices, then Link a device, then "Link with phone number
   instead". Type the code.
4. Paste your OpenRouter key.
5. Press finish setup.

That is the whole thing. The bot starts answering the moment it is linked. If
you want to look around first, press skip setup and fill the rest in later from
Settings.

Only one pairing request runs at a time. Starting a new request also replaces a
failed or parked local bridge, so after removing an older conflicting install or
fixing a temporary startup problem, one new **Send code** tap is enough; the app
does not need to be force-stopped.

> [!IMPORTANT]
> Fresh installs intentionally start with `allow_all=true`. That means direct
> contacts and groups may receive replies as soon as WhatsApp is linked. This is
> deliberate, not a missing access check. For a closed installation, turn off
> **Allow every person and group** and configure the owner/admin/allowlists
> before pairing. Proactive messaging is off by default and the group trigger is
> empty by default.

One thing worth doing on the way out: Android's battery optimiser will kill the
connection when the screen has been off for a while. Setup offers a shortcut to
the app's system settings. Set battery usage to unrestricted there.

On Android 13 and newer, grant notification permission. The notification is the
low-noise foreground-service control for connection status, pause/resume,
reconnect, and stop; the app does not mirror every WhatsApp message as an Android
notification.

## What it does

**Answers like a person is holding the phone.** A message that arrives while the
account is already in a conversation gets picked up in seconds. One that arrives
after six hours of silence waits closer to twenty minutes. The delay is drawn
from a curve rather than a range, so fast answers stay rare without being
impossible. Long exchanges run out of stamina and the account drops offline for
a while, the way a real one would.

**Splits replies into bubbles** and pauses between them by how long that text
would take to type. Shows "typing" while it does. Marks the chat read before it
answers, not after.

**Sleeps.** Quiet hours take the WhatsApp socket down completely rather than
just muting replies, so the account's last-seen looks like somebody who went to
bed.

**Remembers.** Recent messages stay in the prompt. With the defaults, 10 literal
messages are retained as overlap and a chat-memory fold runs after 150 new
messages: the first automatic fold is therefore at 160 messages and later folds
are every 150 new messages. Both values are configurable. Persona-wide memory
is synthesized from that persona's chat memories. The operator can inspect and
edit stored memories in the app.

**Has personas.** The catalog contains 14 fixed personas plus one editable
`custom` persona. Fixed personas can own a writing style, voice, profile-picture
rotation, and approved images. The custom persona inherits the configured voice
unless the operator changes it. A persona is selected globally or per chat.

**Handles media.** It can analyse incoming images, audio, and video, transcribe
voice notes, generate images when the model requests one, and send TTS voice
notes. Character references are attached to image generation only when the
active persona owns an approved reference and the model says that persona is
visible.

**Reacts, replies, edits, deletes.** Quote-replies land as real WhatsApp quotes.
Emoji reactions go on individual messages. It notices when you edit or delete
something.

**Says it is an AI.** Every other behaviour on this list works to make the
account read like a person; this one does not. Before the first generated reply
in a chat, that chat gets a single message telling it that it is talking to an
AI — its own message, never folded into an answer, once per chat and claimed in
the database so a crash cannot send it twice. It is on by default. The wording
and the switch are both settings, and turning it off makes undisclosed automated
messaging your own responsibility, legally as well as ethically.

**Reaches out on its own,** if you let it. Proactive messages are off by default
and capped hard when on, because unsolicited outbound traffic is the single
riskiest thing an account like this can do.

**Is controllable from WhatsApp itself.** Thirty-one admin commands work from an
admin number, covering settings, personas, memory, blocks and diagnostics.

**Watches its own volume.** Sends per hour and per day are capped, with a warm-up
that starts a fresh install at a quarter of the configured cap and lifts it over
the first days. When a cap holds a reply back, the app says which cap and why.

## Architecture

```text
WhatsApp
  -> embedded Go whatsmeow linked-device core
  -> authenticated, versioned loopback bridge on 127.0.0.1
  -> Kotlin BotEngine, settings, timing, memory, tools and safety policy
  -> OpenRouter and the selected model provider
  -> durable Android outbox
  -> Go core -> WhatsApp
```

Go owns WhatsApp protocol transport, pairing, replay journaling, idempotency,
and temporary media. Kotlin owns product behavior, prompts, model calls,
personas, persistent memory, access policy, durable delivery, service lifecycle,
and the operator UI. SQLite and app-private files hold recoverable state; there
is no application backend.

## What it costs

Every visible reply is one model call. Tool calls and the optional verifier add
more. The defaults are picked for cost rather than for benchmark scores:

| Job | Default model |
| --- | --- |
| Replies | `deepseek/deepseek-v4-pro-0813` |
| Verification | `deepseek/deepseek-v4-flash-0731` |
| Incoming media analysis | `google/gemini-3.7-flash` |
| Image generation | `openai/gpt-image-2` |
| Audio transcription fallback | `openai/gpt-transcribe` |
| Voice-note TTS | `google/gemini-3.1-flash-tts-preview` |

The settings catalog allows other models, but compatibility is capability-
dependent: not every model supports tools, media, image generation, structured
output, TTS, or the configured reasoning controls. The prompt keeps its stable
prefix in front and volatile context at the end to preserve provider prefix
caching where the selected provider supports it.

## Where your data lives

Persistent application data lives in app-private storage. Network data leaves
the phone in two directions: WhatsApp protocol traffic goes to WhatsApp, and the
configured prompt context plus selected media/model inputs go through OpenRouter
to the chosen provider. Provider processing and retention follow those services'
terms, not this repository.

- Messages, memories and settings sit in an app-private SQLite database.
- The OpenRouter key and the bridge token are encrypted with an AES key that
  lives in the Android keystore and never leaves it, so they are not in the
  settings database in readable form.
- The WhatsApp linked-device credentials are a different thing and are not in the
  keystore: `whatsmeow` keeps them in its own SQLite store in app-private
  storage, protected by Android's app sandbox and excluded from backup. Root or
  an unlocked bootloader crosses that boundary.
- Incoming WhatsApp media is bounded to 64 MiB per item and staged in app-private
  storage before analysis. It is temporary rather than RAM-only: records and
  files expire after 48 hours, with cleanup every six hours while the native
  runtime is alive and once more at startup.
- The Go core binds to loopback only and requires a random 256-bit token. Android
  generates it once when needed, stores it encrypted, and reuses it; it is not a
  per-session credential. Nothing on the local network can reach the bridge.
- Structured activity rows avoid message bodies, prompts, responses, API keys,
  and raw exception messages. They can contain app-private chat identifiers
  needed to attach an event to the correct chat. Native warnings redact common
  JIDs, phone-like values, credentials, pairing codes, and personal paths before
  logcat, but logcat remains diagnostic data.
- Settings has a switch that masks phone numbers on screen, for screenshots.
- An operator-triggered export writes the database and approved images into one
  archive. That archive contains private transcripts, identities, settings, and
  media; store and share it like a credential. Import replaces local app data.

Android permissions are limited to networking, network state, notifications,
boot restart, foreground remote-messaging service, wake lock, and Wi-Fi state.
The app has no contacts, SMS, microphone, camera, location, accessibility, or
storage-wide permission. Android backup and device-transfer backup are disabled.

## Current limitations

- This is an unofficial linked-device client and can trigger WhatsApp account
  restrictions. A spare account and low traffic are strongly recommended.
- The checked-in native AAR is arm64-v8a only.
- Model availability and capability can change independently of the app.
- Some OEM battery managers still stop long-running foreground services unless
  the operator grants unrestricted battery use.
- A successful build proves source compatibility, not live WhatsApp delivery,
  provider compatibility, background survival, or freedom from restrictions.

## Build from source

You need JDK 21, the Android SDK, and the repository. Source and bytecode
compatibility remain Java 17; JDK 21 is the tested Gradle/AGP runtime.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. It is for development
only: a diagnostic build exposes app-private state to `adb run-as`, so it must
never be handed to anyone else.

If you only want to run the app, you do not need any of this — take the signed
APK from the [releases page](https://github.com/totec448-spec/doppel/releases/latest).

A build you intend to hand to somebody else is `assembleDistributionRelease`, and
only that. It fails closed when the signing credentials are missing:

```powershell
$env:DOPPEL_RELEASE_KEYSTORE = "path\to\your.jks"
$env:DOPPEL_RELEASE_STORE_PASSWORD = "..."
$env:DOPPEL_RELEASE_KEY_ALIAS = "..."
$env:DOPPEL_RELEASE_KEY_PASSWORD = "..."
./gradlew :app:assembleDistributionRelease
```

Plain `assembleRelease` is the local-testing path. It builds the same minified,
non-debuggable code, but when those four variables are absent it signs with the
local debug key so `adb install -r` can upgrade an existing install. That APK is
for your own phone. It is not a distributable artifact, whatever the file name
says. Normal push CI runs tests and lint without producing an APK; only the tag
release workflow invokes the protected distribution build.

Tests:

```powershell
./gradlew :app:testDebugUnitTest
```

Do not run a `lint` task in the same invocation as the unit tests. The lint
worker holds on after `:app:lintRelease` and starves the test task into a
timeout that looks like a hang.

### Rebuilding the Go core

`app/libs/nativewa.aar` is checked in, so a normal build never touches Go. If
you change anything under `native-wa/`, rebuild it:

```powershell
./native-wa/build-android.ps1 -GoRoot "C:\path\to\go" -AndroidNdkHome "$env:LOCALAPPDATA\Android\Sdk\ndk\<version>"
```

Both paths are required. Pass the full path of a real Go SDK: the trimmed
`go.exe` in the repository is not usable on its own. The script stages the module
outside your user profile before building, because `gomobile` records its module
path in `.go.buildinfo` even under `-trimpath`, and building straight from a home
directory bakes that path into `libgojni.so`.

## Licence

This repository is licensed GPL-3.0-only. The project selected that license to
remain compatible with the GPL-3.0 `go.mau.fi/libsignal` code linked into the
native core. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The practical consequence: anyone distributing a build of this has to make the
matching source available. Every release here is tagged with the commit it was
built from.

`go.mau.fi/whatsmeow` is MPL-2.0 and is used unmodified at a pinned revision.
There is no fork, no vendored copy, no patch and no `replace` directive. All of
the WhatsApp glue in `native-wa/` is separate code calling its public API.
Details are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
[docs/WHATSMEOW_INTEGRATION.md](docs/WHATSMEOW_INTEGRATION.md). The full licence
texts ship inside the APK under `assets/licenses/`.

The bundled persona images are synthetic assets generated for this project from
text prompts, carry no embedded metadata, and are distributed under the same
license as everything else. Their provenance is recorded in
[ASSET_LICENSE.md](ASSET_LICENSE.md).

## Reading further

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the layer boundaries.
- [docs/SETTINGS_AND_COMMANDS.md](docs/SETTINGS_AND_COMMANDS.md) for all 88 live
  settings and all 31 commands.
- [docs/SETUP.md](docs/SETUP.md) for pairing, permissions and battery setup.
- [docs/SECURITY.md](docs/SECURITY.md) for the threat model.
- [docs/RELEASE.md](docs/RELEASE.md) for the first-release gate and clean-history
  publication procedure.
- [PRIVACY.md](PRIVACY.md) for what leaves the device.
