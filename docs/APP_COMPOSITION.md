# How the Android app is put together

## One shared graph

`WhatsAppBotApplication` builds exactly one `BotAppGraph`, holding the
repository, the settings repository, the keystore-backed secret store, the
`AppControlRegistry` and one shared HTTP client. Compose, the admin commands and
`NativeRuntimeHost` all use those same instances. There is no second UI database
and no parallel admin or settings implementation.

Persona seeding runs on an IO dispatcher after a minimal process start. A
process that only came up to host the foreground service never builds chat lists
or Compose projections.

## Controllers and visible work

`ProductionAppController` projects global settings, runtime state, metrics,
pairing and admin results. `ProductionChatsController` owns the chat roster, the
chat detail, the live turn, uptime and the known identity bindings.

The root composable collects only the flows of the branch that is on screen:

- setup collects no roster, uptime or detail;
- the chat list collects rows and uptime;
- an open chat collects detail, the live turn and the row activity it needs;
- settings collects uptime and bindings, with no chat detail projection.

The controllers additionally gate their own database collectors and one-second
tickers on `subscriptionCount`. Leaving a surface cancels that work, and coming
back creates exactly one new collector. Detail loads carry a generation token, so
a slow old read can never overwrite a newer write.

## Settings

`BotSettingsSchema` holds 88 live bot settings. Installation-level values live in
`AppSettingsSchema`. Removed bridge modes and `leave_on_read` survive only as
legacy keys that are explicitly discarded on import.

The UI and the commands share `SettingInputParser`, `parseBooleanInput` and
`normalizePhoneNumber`. Coupled values such as the TTS model and voice are
normalised in the repository. `ProductionAppController` writes the already typed
value straight through rather than serialising it back into a simulated admin
command first.

Global settings are the rule. The only per-chat values are persona, reply preset,
proactivity, and in groups the trigger. There is no per-chat model and no
per-chat words-per-minute.

## UI structure

The app opens on the chat list. A single searchable settings root absorbs
overview, access, blocks, tools, safety, memory, battery and activity. Deeper
levels use a saveable back stack. Setup is a one-page form covering the admin
number, the OpenRouter key, start, the pairing code and done.

Details are in [UI_DESIGN.md](UI_DESIGN.md).

## Runtime bindings

`AppControlRegistry` binds exactly the current bridge client, model options,
admin actions, metrics and activity invalidation. A compare-and-set on detach
stops an old host from unregistering a newer connection.

`RuntimeBridgeControl.refreshSafety()` is single-flight and caches the same
read-only WhatsApp recheck for 30 seconds. The Go core carries the same guard,
and a confirmed block or unblock invalidates the cache. Switching persona can
trigger exactly one explicit push-name write, never a poller.

## Idle and write cost

- no UI polling without a visible subscriber;
- no periodic safety recheck;
- one persisted single-due scheduler instead of a scanning timer;
- reschedule bursts are coalesced;
- AI telemetry goes through a bounded IO writer;
- successful outbox transitions do not emit a debug line per step;
- the roster uses a bounded SQL preview instead of full message records;
- memory refresh checks the cadence cheaply before scanning any history;
- prompt messages are materialised once per bundle.

The runtime itself deliberately holds exactly one WhatsApp session and one
loopback WebSocket. Reconnect has a single owner. Read and played receipts are
deduplicated and batched. Group metadata is loaded once per runtime, direct names
are taken from events, and profile pictures are never polled.

## What the tests do and do not prove

Deterministic JVM and Go tests check the local contracts. They prove neither real
provider compatibility nor freedom from WhatsApp enforcement, and they say
nothing about doze behaviour or the visual phone UI. Build, lint, the native AAR,
the release APK, installation and later live scenarios are therefore tracked
separately.
