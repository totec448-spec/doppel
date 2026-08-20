# Setup and operation

## If you have no idea what to do yet

The system has exactly two parts, and both live on the phone:

1. **The Android app:** bot logic, settings, rules, personas, the local history
   and OpenRouter access.
2. **The native WhatsApp core:** a small Go core (`whatsmeow`) shipped inside the
   APK. It holds the linked WhatsApp session and talks to the app only over
   `127.0.0.1:18787`.

There is no server, no PC process, no Node.js and no address you have to enter
anywhere. WhatsApp treats the core as a linked device, exactly like WhatsApp Web.

For a first full setup you need:

- an Android phone (arm64, Android 9 or newer);
- an OpenRouter API key;
- your WhatsApp number in international form.

The app shows every necessary step on one scrollable page. After that the
OpenRouter key and the WhatsApp link are two independent settings disclosures.

### The terms without the jargon

| Term | What it is | Where it comes from | How important |
|---|---|---|---|
| Native core | The WhatsApp connection inside the APK | Ships with the app, nothing to do | automatic |
| Loopback token | Password between the app and the core on the same device | The app generates and stores it itself | automatic |
| OpenRouter key | Pays for and authorises the AI models used | Created in your own OpenRouter account | required and secret |
| STT key | Separate legacy fallback for speech recognition only | An external STT provider | optional, normally empty |
| Admin | Optional number for remote control over WhatsApp | An international WhatsApp number, digits only | optional |

The loopback token is **not** a WhatsApp password, **not** a pairing code and
**not** an OpenRouter key. It stops another app on the same phone from talking to
the local port, and you are never asked for it.

## Quick start

1. Build the APK (see below) and install it.
2. Open the app. The assistant starts by itself.
3. Enter the WhatsApp number and the OpenRouter key. **Admin number** is
   optional.
4. Choose **Send code**. The app starts the native service it needs on its own.
5. In WhatsApp, go to **Linked devices**, **Link a device**, **Link with phone
   number**, and type the code.
6. Wait for **Connected** and choose **Done**.
7. If the installation should be closed, disable **Allow every person and
   group** and configure the owner/admin/allowlists before pairing.

## Requirements

Android:

- Android Studio JBR / Java 21 (with Java 17 source/bytecode compatibility)
- Android SDK 37 to compile
- target app: `de.totec.doppel`
- minSdk 28, targetSdk 36
- a dedicated WhatsApp test number is strongly recommended

Cleartext traffic is disabled app-wide. The only exception is `127.0.0.1` and
`localhost` for the bundled core.

## Building Android locally

```powershell
Set-Location <checkout>
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Release:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:lintRelease :app:assembleRelease
```

Never start a lint task in the same invocation as `testDebugUnitTest`. The lint
worker blocks afterwards and lets the tests run into a fake timeout.

The release build is minified and uses resource shrinking. A build proves neither
live WhatsApp behaviour nor provider or background behaviour.

`assembleRelease` is the local release-validation path. Without the four
`DOPPEL_RELEASE_*` variables it signs with the debug key so `adb install -r`
can upgrade an existing install, which means its APK is for your own phone and
nobody else's. Anything you hand out is built with `:app:assembleDistributionRelease`,
which refuses to run unless real signing credentials are present.

Rebuilding the native AAR is only necessary when `native-wa/` changed:

```powershell
.\native-wa\build-android.ps1 -GoRoot C:\path\to\go -AndroidNdkHome C:\path\to\ndk
```

### Signing an APK other people install

You need your own key. Create it once, in your own terminal, and keep it
somewhere you will still have it in two years:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore doppel-release.jks -alias release `
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` asks for the passwords instead of taking them as arguments, so they do
not end up in your shell history. `.gitignore` already excludes `*.jks`,
`*.keystore`, `*.p12`, `*.pem` and `*.key`, so the file cannot be committed by
accident.

This key is not recoverable and not replaceable. Android only installs an update
over an existing copy when both carry the same signature, so losing the key means
every existing installation is stranded on its current version, with the settings
and the WhatsApp link inside it. Losing control of the key is worse: whoever holds
it can build something Android accepts as this app.

Building locally, in a shell where the passwords are typed rather than written
down:

```powershell
$env:DOPPEL_RELEASE_KEYSTORE = (Resolve-Path .\doppel-release.jks).Path
$env:DOPPEL_RELEASE_KEY_ALIAS = 'release'
$env:DOPPEL_RELEASE_STORE_PASSWORD = Read-Host 'Store password'
$env:DOPPEL_RELEASE_KEY_PASSWORD = Read-Host 'Key password'
.\gradlew.bat --no-daemon :app:assembleDistributionRelease
```

Verify what came out before handing it to anybody. A debug signature is the one
failure that looks like success:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify `
  --print-certs app\build\outputs\apk\release\app-release.apk
```

`CN=Android Debug` means the environment variables were not picked up. The
distribution task fails closed rather than producing that, but the check costs
nothing and the mistake is expensive.

### Publishing a release from CI

`.github/workflows/release.yml` does the same build on a tag push and attaches
the APK and its SHA-256 to the GitHub release. It needs four repository secrets,
added under Settings, Secrets and variables, Actions:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `RELEASE_STORE_PASSWORD` | store password |
| `RELEASE_KEY_ALIAS` | key alias, `release` above |
| `RELEASE_KEY_PASSWORD` | key password |

Encode the keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(
  (Resolve-Path .\doppel-release.jks).Path)) | Set-Clipboard
```

Then tag and push:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

The workflow refuses to publish an APK whose certificate says
`CN=Android Debug`, so a misconfigured secret produces a failed run rather than a
release everybody can forge updates for.

The normal push workflow runs hygiene, Go test/vet, Android unit tests, and
debug/release lint only. It deliberately creates no APK; APK assembly is reserved
for this tag/manual release workflow.

### Diagnostic build (`run-as`)

For debugging on the device:

```powershell
.\gradlew.bat --no-daemon :app:assembleDiagnostic
adb install -r app\build\outputs\apk\diagnostic\app-diagnostic.apk
adb shell run-as de.totec.doppel ls -l databases
```

The separate diagnostic variant is debuggable and intentionally unminified. Its
version carries the `-diag` suffix. The normal optimized release variant cannot
be made debuggable by a Gradle flag.

Do not ship it. In a debuggable package any adb client can read the paired
WhatsApp session credentials, and that is the linked-device identity, not just
app data. After finishing diagnosis, install an APK without the flag again.

## First run inside the app

The single setup page uses this order:

1. Telephone number;
2. Send code;
3. the short-lived pairing code and copy action;
4. OpenRouter API key;
5. Admin number (optional);
6. Done.

One Send code tap waits for the native bridge's action-ready frame and then
requests the code. The help path is WhatsApp's three-dot menu, **Linked devices**,
**Link a device**, then **Link with phone number**. The copy action writes only
the code characters to the normal Android clipboard.

The expandable **Admin number (optional)** field holds only the admin number. An
owner or admin number is not required for pairing or for normal bot operation.

There is no page wizard and no transport choice in the quick setup. The product
path uses the native linked-device core contained in the APK.

After onboarding, the OpenRouter key field saves changes automatically and
independently of the WhatsApp pairing. An admin number that is entered is also
stored as an allowed direct contact, but is not silently promoted to owner.

Fresh installs intentionally start with every direct contact and group allowed,
Instant reply speed, Default battery mode, image generation enabled, and start
after reboot enabled. Turn **Allow every person and group** off before connecting
if the installation should begin with an explicit allowlist instead.

## Pairing with WhatsApp

1. Start the Android service, so `RuntimeBridgeControl` has a live client.
2. In the pairing area, enter the WhatsApp number as 6 to 15 international
   digits.
3. Tap **Send code** once.
4. In WhatsApp open the three-dot menu → **Linked devices** →
   **Link a device** → **Link with phone number**.
5. Type the short-lived code.
6. Wait for `connected`.

Only one request is accepted at a time. **Send code** is also a recovery action:
it replaces a failed or terminally parked local bridge before requesting the
next code. If an older bridge app was still using the same local port, stop or
uninstall that app and tap **Send code** once more; force-stopping Doppel is not
required.

The sequenced event never carries the code. The code arrives only in the direct
action result and is not persisted. There is a limit of three requests per ten
minutes.

Repeated unlink and relink is not a normal debugging step. For ordinary failures,
check network status, battery optimisation and logcat first.

## Start, automatic recovery and stop

- **Start** sets `runRequested=true`, starts the foreground service and connects.
- **Stop** sets `runRequested=false` first, then closes the runtime and removes
  the notification.

The normal app surface deliberately shows only the current connection status and
exactly one start/stop action. It has no manual reconnect button and no separate
"Reply automatically" button. Network changes and transient failures go through
the single runtime reconnect owner with full-jitter backoff. The internal
reconnect command still exists for the notification, for diagnosis and for
orderly recovery, and it never starts a second host.

Notification:

- tapping it opens the app;
- the replies on/off action pauses or enables normal and proactive replies, while
  the connection and admin commands stay online;
- the reconnect action reconnects;
- the stop action stops completely.

Autostart only takes effect once setup is complete, and Android or OEM background
rules can prevent it. A stop from the task manager is respected through exit
history, as long as there is no newer explicit start. The overview opens the
app-specific system page directly through the battery settings entry. Reliable
long-running operation can require "unrestricted" on a given manufacturer, and
Android guarantees no unkillable process.

## Models

When a model or voice picker opens, the app loads the OpenRouter catalogue
automatically. Search and a hand-typed slug share one field, and a catalogue tap
or enter saves immediately. Switching the TTS model also sets a default voice
offered by the new model. Being in the catalogue does not guarantee support for:

- tools;
- image, audio or video;
- speech and TTS;
- reasoning parameters;
- streaming.

Every combination you pick needs a test with real credentials.

## Data and backups

Everything lives in app-private Android storage:

- `whatsapp_bot.db`: chat and bot state, no raw credentials;
- the whatsmeow device store, the replay journal and the core's idempotency
  table;
- encrypted credential shared preferences;
- short-lived private media and voice caches;
- approved image assets under `filesDir/approved-media/v1`.

Before updates:

1. Stop the bot.
2. Update the app with a data-preserving install.
3. Start the bot again.

As long as the whatsmeow device store survives, no new pairing is needed.
Uninstalling deletes it and forces a new link.

## Approved persona images

In the admin area:

1. Pick a persona.
2. Open the Android file picker.
3. Choose JPEG, PNG, GIF or WebP up to 16 MiB.
4. Refresh the list.
5. Delete assets you no longer want, directly in the same list.

The import copies the bytes into private app storage and does not keep the
external URI. The AI sees only an opaque ID and metadata. Sending by AI is
available only when the active persona owns an approved asset.

## Troubleshooting

### The pairing code does not arrive

- the bot service has to be running;
- the number must be digits only;
- wait for the current request to finish instead of tapping repeatedly;
- stop or uninstall an older bridge build that may still own the local port,
  then tap **Send code** once; the new request restarts Doppel's parked bridge;
- mind the rate limit of three requests per ten minutes;
- a session that is already paired needs no new code.

### Resume gap

The core reconciles the cursor, and Android then validates the gapless replay.
`gap/reset` raises a persistent hard safety lock, and no bot output continues
silently. Check the journal and database state, and only confirm or clear the
lock after a deliberate admin decision.

### The bot is paused but commands still answer

Expected. Pause affects normal and proactive behaviour, not the recovery and
admin channel.

### The notification disappears

Check:

- an explicit stop;
- a stop from Android's "active apps";
- force stop;
- the notification permission;
- the OEM battery manager;
- service start failures in logcat.

### No live evidence

A green build is not evidence about WhatsApp or OpenRouter. It proves compilation
and deterministic logic, not behaviour on the real network.
