# whatsmeow integration

## Upstream boundary

The app consumes `go.mau.fi/whatsmeow` at
`v0.0.0-20260814123134-0dcf1f50f4b1`. There is no vendored whatsmeow tree, fork,
patch file, or `replace` directive. No upstream whatsmeow source file has been
modified. The exact MPL-2.0 source is available at
https://github.com/tulir/whatsmeow/tree/0dcf1f50f4b1.

Everything under `native-wa/` is the app-owned adapter around that dependency.
It is compiled with gomobile into `app/libs/nativewa.aar`; Kotlin never embeds a
second WhatsApp client.

## Modification declaration

There are no modifications to upstream whatsmeow source files. The module is
resolved at the exact revision above without a `replace` directive, vendor tree,
fork or patch. Every product-specific difference listed below is implemented in
new app-owned adapter files which call whatsmeow's public API. In particular,
the linked-device product name, Android lifecycle, local protocol, safety
projection and action mapping do not alter an upstream MPL-covered file.

## App-owned adaptations around whatsmeow

- **Android lifecycle:** one process-global client survives foreground-service
  restarts. Sleep disconnects the socket without deleting the device; wake
  reconnects the same identity. Logout creates a fresh device/client because a
  deleted whatsmeow device cannot be reused.
- **Client policy:** auto-reconnect, automatic phone rerequests, active delivery
  receipts, automatic identity trust and whatsmeow's persisted retry-message
  store are enabled for unattended linked-device operation.
- **Pairing:** a bounded `PairPhone` flow exposes one short-lived code through an
  authenticated action. The Android controller waits for replay-ready state
  before sending the action.
- **Authenticated local boundary:** the Go runtime binds only to Android
  loopback. A random 256-bit bearer token authenticates versioned WebSocket
  event/action frames and bounded HTTP media reads.
- **Crash recovery:** inbound events enter a durable sequence journal; Android
  acknowledges only after disposition. Outbound actions use stable IDs and a
  native idempotency journal, complementing Android's durable outbox.
- **Inbound normalization:** whatsmeow events become typed messages, reactions,
  edits, deletes, replies, receipts, calls, safety signals and media references.
  LID/phone aliases are resolved through whatsmeow's recorded mapping while the
  original chat JID remains the conversation owner.
- **Ordering and backpressure:** four chat-hashed inbound lanes preserve order
  inside each conversation while preventing a slow attachment from blocking all
  other chats. Duplicate stanzas are claimed before any media download.
- **Media bounds:** access and media-kind policy run before download. Encrypted
  files use a bounded writer so remote `Content-Length` cannot preallocate an
  unbounded file; temporary files have short lifetimes and app-private modes.
- **Native actions:** text, per-bubble replies, reactions, media, presence,
  receipts, edit/delete, block/unblock and profile operations map to the pinned
  whatsmeow API behind one validated action dispatcher.
- **Power behavior:** link sleep disables auto-reconnect before disconnecting;
  link wake restores it. This prevents a socket race from defeating Android low
  battery mode without turning sleep into a destructive relink.
- **Safety projection:** keepalive loss, temporary bans, stream replacement,
  logout, connection storms, identity changes, retry requests, 463 reach-out
  limits and message capping become bounded structured events. Blocklist and
  safety queries are cached/coalesced to avoid a polling signature.
- **Protocol compatibility:** error classification prefers typed
  `whatsmeow.IQError` values, with a guarded fallback only where upstream exposes
  message-send status in a formatted error. Tests pin the expected 400/419/429/463
  behavior and fail visibly if a future dependency update changes it.
- **Privacy:** normal inbound text has OpenRouter-shaped secrets redacted before
  Android receives it. whatsmeow logging is restricted to technical WARN/ERROR
  diagnostics; app activity logs do not store message bodies, phone numbers,
  prompts, responses or API keys.

## What is deliberately not changed

- whatsmeow's encryption, Signal sessions, protobuf schema, app-state engine,
  upload/download crypto and WhatsApp Web protocol implementation remain
  upstream-owned.
- Kotlin owns prompts, personas, AI calls, timing, memory, access decisions,
  final-send safety and UI. None of that is implemented inside whatsmeow or the
  native adapter.
- The adapter does not periodically poll chats, contacts, profiles or presence.
  It consumes pushed events and performs only bounded fetches with a concrete
  delivery, safety or visible UI purpose.

## Updating the dependency

An update is complete only when `go.mod` and `go.sum` are pinned, native Go tests
pass, the ARM64 AAR is rebuilt with `native-wa/build-android.ps1`, the checked-in
AAR changes are reviewed, Android unit/lint/release gates pass, the notice pin is
updated, and pairing plus one non-critical live send are reverified on a device.
