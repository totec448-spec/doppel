# Security and privacy

This architecture reduces the risk of attack and of misoperation. It cannot
guarantee the security of an unaudited deployment, and it cannot protect against
WhatsApp restrictions.

## Trust boundaries

```text
WhatsApp
  -> native whatsmeow core inside the APK
  -> 127.0.0.1:18787 + bearer token
  -> Android app
  -> OpenRouter
```

The chain leaves the phone towards WhatsApp and towards OpenRouter. A third
route exists only if you configure it: when a transcription endpoint is set,
voice-note audio is uploaded directly over HTTPS to that endpoint with its own
separately stored credential, bypassing OpenRouter entirely. That provider's
retention terms then apply to the audio. There is no foreign host beyond the
ones you configure, no reverse proxy, and no bridge open to the outside.

At minimum, these are confidential:

- the whatsmeow device store, which is an active WhatsApp linked-device session;
- the core's replay journal, idempotency table and cached media;
- the loopback token;
- OpenRouter and optional STT credentials;
- the Android database with messages, names, memories, settings and activity;
- local logs and backups, and every chat or media snippet sent to OpenRouter.

## Secrets on Android

`SecretStore` accepts only three fixed names:

- `openrouter_api_key`
- `bridge_token`
- `stt_api_key`

The value is encrypted with AES-256-GCM. The non-exportable wrapping key lives in
the Android keystore. Ciphertext and IV live in app-private shared preferences.
Auto Backup is disabled in the manifest, so no undecryptable ciphertext is ever
restored without the device-bound key.

The key deliberately carries no biometric or unlock requirement. A background bot
the user started has to be able to read credentials unattended. That is an
availability against security trade-off, not full isolation from a compromised
device.

The UI and chat show at most the last four characters of an API key. The bridge
token is not returned even masked. Runtime logs write only error classes and
redacted short texts, never provider bodies.

The SQLite database itself is not additionally encrypted at the application
level. It is protected by Android's app sandbox. An unlocked or rooted device, an
ADB backup with the right permissions, or compromised system software can cross
that boundary.

## Authenticating the native core

The core:

- binds `127.0.0.1:18787` only, with no configurable alternative;
- accepts bearer auth in the header only, never in a query, cookie, subprotocol
  or WebSocket frame;
- requires a token of at least 32 random bytes, encoded as at least 64 hex or 43
  base64url characters;
- compares in constant time;
- bounds HTTP by size and timeouts. Concurrent native actions are hard limited
  and further frames get backpressure. Pairing has its own state and time bounds;
- disables WebSocket compression and bounds frame and buffer sizes.

The token is not a formality. Loopback is reachable device-wide, so without it
any other app on the same phone could send messages. Android generates and stores
it in the keystore itself, and it is never requested or displayed.

`GET /v1/health` requires bearer auth and carries a coarse WhatsApp state plus the
app's own linked account identity, which the local profile display needs anyway.
No endpoint carries the token, foreign contacts, paths, messages or the pairing
code.

There is deliberately no TLS on this hop, because it never leaves the device.
Cleartext is permitted by the network security policy for `127.0.0.1` and
`localhost` only.

## Pairing and auth material

The pairing code is returned only in the matching `action_result`. The sequenced
`pairing_code` event carries availability and expiry alone. The code is persisted
neither in the journal nor in the idempotency table.

The whatsmeow device store is effectively a WhatsApp login. If you suspect the
device is compromised:

1. Stop the bot.
2. Unlink the linked device in WhatsApp.
3. Clear app data and pair again.

It lives in app-private storage and is excluded from Auto Backup. An unlocked or
rooted device crosses that boundary regardless.

## Protocol and media hardening

- Protocol version, frame type, required fields, IDs, JIDs and enums are
  validated fail-closed.
- Broadcast and status JIDs are not valid action targets.
- The action ID is the persisted idempotency key.
- Reusing the same ID with a different payload raises a conflict.
- A crash inside the ambiguous send window produces `idempotency_in_doubt`.
- Resume replay is delivered in full up to the welcome snapshot, in bounded pages
  with WebSocket backpressure, rather than being truncated after the first page.
- Media moves as a raw stream over HTTP on loopback, never as base64 inside the
  WebSocket.
- The file size is declared and counted during the stream.
- `Content-Encoding`, header injection, invalid opaque IDs and path traversal are
  rejected.
- File name, total volume and TTL are bounded.
- The core forwards `whatsmeow` diagnostics to logcat at WARN and above
  (`adb logcat -s GoLog`). Before writing, the shared logger redacts common JIDs,
  phone-like values, bearer/OpenRouter-shaped credentials, pairing codes, and
  personal workstation paths. Panic payloads and stacks are not logged. This is
  defense in depth, not permission to log messages or opaque protocol payloads;
  logcat is private diagnostic data. Inbound text has OpenRouter-shaped secrets
  replaced before it is published or journalled, so a key somebody messages to
  the bot is not written down anywhere in clear.

## Access and admin protection

Defaults:

- `allow_all=true` so new installations answer direct contacts and groups immediately;
  operators who need a closed bot should disable it before connecting WhatsApp
- separate allowlist and group allowlist
- owner and admins explicit
- the group trigger empty by default
- cross-chat search off by default
- proactive level 0 by default

Admin commands are processed before the functional bot pause, but only after
admin resolution. `/apikey` is additionally restricted to direct chats. Owners
cannot be removed from the admin list in the normal app surface. Block operations
protect configured owners and admins.

A wipe from a chat uses a short-lived six-digit challenge code. It is bound to
actor, originating chat, target and operation. Wipes from the app have a
confirmation dialog.

## The model and tool boundary

The model receives only the tools that the settings and the turn mode allow.
Side-effect tools do not send directly:

1. Parse and validate the tool call.
2. Hold it as a `PendingAction`.
3. Optionally verify the whole reply.
4. Only then move it into an engine side-effect structure.
5. Reserve against the outbound policy.
6. Execute the transport action in the core.

On malformed tool JSON, an incomplete stream or a rejecting verifier, nothing
visible is committed.

The verified memory refresh explicitly treats history and existing summaries in
the utility prompt as untrusted data, bounds input and output, and writes chat and
persona memory atomically. An invalid result or an error never overwrites good
existing memory. The activity audit contains no chat text. The utility provider
does still receive the bounded message and memory content.

Current limits:

- Cross-chat tool results are filtered to the active persona and hand OpenRouter
  only opaque IDs salted per process. After a process restart old model IDs are
  deliberately invalid, and JIDs and phone numbers stay outside that tool result.
- The persona already resolved for the chat is used jointly for prompt, memory,
  voice, approved media and cross-chat search.
- The image tool is only exposed when the active persona owns at least one
  approved asset. It sees only the opaque ID, display name, MIME type and size,
  never a URI, path or hash.
- The block tool path is limited to the current sender, is terminal within the
  turn, and is protected by the same local admin and owner immunity as manual
  blocks. PN and LID alias equality is implemented in the core but needs live
  WhatsApp evidence.
- Chat and persona memory and history go to the selected provider in clear text,
  to the extent they sit inside the prompt window.

## Incoming media

Before any media download, Android pushes a fail-closed ingress policy to the
core carrying the allowed PN, LID and group identities. Only an event permitted
there may materialise media. The core writes the decrypted file to app-private
storage (48 h retention, swept every 6 h). The Kotlin side fetches it over
loopback and holds it in RAM only: checked by SHA-256 and a size limit, analysed,
and gone with the turn. The app never writes incoming media to storage, not even
briefly, so a process kill can leave nothing behind. SQLite stores only the
bounded text result.

The provider request does, however, contain the corresponding image, audio or
video bytes. That is a deliberate external disclosure and should be enabled only
for providers and models you actually want.

Audio and video conversion briefly creates additional in-process copies.
`MediaExtractor` reads through a `MediaDataSource` adapter directly from the
loaded byte array, and the decoded PCM grows alongside it up to the analysis
limit.

## Approved outgoing images

The user imports images exclusively through Android's Storage Access Framework.
The import:

- accepts JPEG, PNG, GIF and WebP only;
- decides on magic bytes, not just the provider MIME type;
- streams with a 16 KiB buffer and a 16 MiB maximum;
- stores under `filesDir/approved-media/v1`;
- uses IDs of `img_` plus 32 lowercase hex characters;
- publishes data and metadata atomically;
- deduplicates identical bytes within the same persona;
- persists no external content URI.

Before upload, the ID, persona, canonical parent, length and SHA-256 are
validated again. Original names are bounded display labels only and never select
a path. Manual sending is restricted to `AdminOrigin.APP` and a running bridge.
Delete is confirmed.

The AI path stays a pending action until after the verifier, and then uses the
normal media, safety and bridge path. The manual send from the app uses the same
private store, upload and transport mapper. It reserves the outbound safety
ledger path with `admin=true` and keeps the same request ID all the way through
the Android outbox and the core. Hard locks, review and holds apply, while soft
admin velocity exemptions remain as the policy defines them. This direct admin
path does not create assistant history.

With `block_repeat_images=true`, an empty marker under the asset ID and the
SHA-256 of the chat ID is published after a durable successful transport result.
It stores no clear-text JID. A failure before any effect stays retryable, and an
ambiguous result stays fail-closed. The marker is not evidence of final WhatsApp
delivery or of a read receipt.

## Outbound safety: what exists

The native policy checks:

- local blocks;
- a local manual safety hold;
- an identical payload within a short window;
- global minimum spacing;
- hourly and daily budgets;
- for proactive additionally the daily cap, the inbound ratio and the cold
  contact cap.

Auto-block checks the configured 1, 5 and 10 minute flood windows only. The
installation-level rate window is a separate soft drop and must never create a
permanent block. Admins are exempt from the flood auto-block.

The core rejects broadcast and status and invalid targets, and reports coarse
safety telemetry.

## Outbound safety: what is still missing

None of these may be advertised as fully implemented:

- Restriction, logged-out, bad-session, manual-review and replay gap or reset are
  translated into hard persistent locks. WhatsApp still offers linked devices no
  complete `used/total`, OTE or 463 quota API.
- `/reachout` and `/safety refresh` query the timelock and new-chat cap read-only
  through the native core. Android and Go merge parallel rechecks single-flight
  and cache the same result for 30 seconds. Live account evidence stays separate
  from source and parser evidence.
- `used/total_quota`, OTE and capping, and 463 do not produce a complete native
  adaptive posture.
- Delivery failures do not create the old reject-window or restriction pause.
- Fresh-login and recovery warm-up scale hourly, daily and proactive budgets and
  the global send spacing. A server-side WhatsApp warm-up status cannot be
  queried.
- Cross-chat transcript access is controlled by the explicit `cross_chat_search`
  tool gate. Media ingress remains behind the normal allow-all/allowlist and
  per-kind gates; there is no second overlapping Strict Privacy switch.
- There is no native link or shortener guard.
- Owners get no automatic alert when a restriction begins.
- The outbound policy has a preflight before read, presence and the main model,
  and a final check with the payload hash. Visible side effects and TTS and media
  materialisation only start after the final reservation.
- Reading facts and reserving are not implemented as one global SQLite
  transaction. The single visible engine serialisation reduces but does not
  replace that formal protection.
- Android enforces contiguous sequences and translates `welcome.resume.gap/reset`
  into a safety lock that has to be acknowledged.
- The active database outbox protects individual transport actions, but not the
  atomic completion of a whole multi-bubble side-effect turn including assistant
  history and the safety ledger.

## Account risk

whatsmeow is an unofficial WhatsApp implementation. No code and no documentation
can promise freedom from bans or restrictions. For testing, use a dedicated
number, leave `proactive_level=0`, leave `allow_all=false`, and keep the volume
low.

On a restriction, a 463, unusual delivery failures or pairing problems:

- stop the service;
- do not reconnect or re-pair aggressively;
- check the account and linked devices directly in WhatsApp;
- save the activity log and the local safety entries;
- continue only once the cause is understood.

## Deployment checklist

- No debug or `diagnosticBuild` APK in continuous operation.
- A device with a screen lock, not rooted.
- `allow_all=false`, and enable cross-chat only deliberately.
- Proactive at 0 to begin with.
- An OpenRouter key with a budget limit.
- Grant the notification permission.
- No private messages in screenshots or logs.
- Live test with a secondary account first.
- Close the gaps listed in this document before running high volume in
  production.

## Reporting a vulnerability

Do not open a public issue for a security problem. Use GitHub's private
vulnerability reporting on this repository: the Security tab, then "Report a
vulnerability". That opens a private thread visible only to the maintainer.

Useful in a report: the affected version, whether it needs an operator with
device access or a remote contact, and the smallest sequence that reproduces it.
There is no bounty and no fixed response deadline. This is a spare-time project
run by one person, so an unauthenticated remote path will be looked at long
before a local hardening idea.
