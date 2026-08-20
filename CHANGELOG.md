# Changelog

## 1.0.0 — 2026-08-14 — renamed to Doppel

The version line restarts here. This release changes the application ID, so no
installed copy of the previous build can upgrade into it and no version code has
to stay monotonic across the two; continuing at 1.0.3 would have implied an
upgrade path that does not exist.

- Made **Send code** recover a terminally parked native bridge instead of issuing
  an idempotent start that could leave the request timing out forever. Repeated
  taps are now single-flight rather than queued as multiple reconnects.
- Corrected the project GPL appendix to identify Doppel contributors while
  retaining libsignal's upstream GPL copy in the third-party license directory.
- Documented that bounded incoming media uses a 48-hour app-private disk cache;
  it is temporary, but not RAM-only.
- Restored the final release-gate changes from the renamed local checkout:
  branch pushes no longer duplicate pull-request CI, migration CI is trunk-
  filtered, and the ordinary gate now builds a minified candidate before any
  release tag is cut.
- Created a new Doppel-only RSA-4096 release identity, backed it up outside the
  repository, configured the protected GitHub Actions signing secrets, and
  pinned the public certificate fingerprint for release verification.
- Fixed the clean-runner native rebuild by installing `gobind` alongside
  `gomobile` from the exact same pinned `golang.org/x/mobile` revision.
- Fixed upgrades from sparse v1 databases by creating missing baseline tables
  before later migrations alter them; the API-30 migration test now exercises
  this path instead of failing on a missing `bridge_outbox` table.

- Renamed the product from "WhatsApp AI Bridge" to **Doppel**. Meta's brand
  guidelines forbid using the WhatsApp mark as part of a product or service name
  and explicitly cover variations and phonetic takeoffs, so the old name was not
  defensible and neither were the obvious puns. The rename covers the application
  ID and namespace (`de.totec.doppel`), the Kotlin package tree, the app label,
  the Go module path, the release artifact name, the signing environment
  variables, and `serverName` in the native core — which feeds `SetOSInfo` and is
  therefore the name WhatsApp shows in Linked Devices. Descriptive references to
  WhatsApp are deliberately left in place throughout the code and README:
  nominative use is permitted, and removing it would only make the source
  describe something other than what it does.
- Disclosed the AI to each new conversation before the first generated reply,
  once per chat, claimed through a dedicated `ai_disclosure_sent_at` column so a
  crash between sending and recording cannot produce a second notice. The text
  and the whole behaviour are settings, off-switch included.
- Fixed profile "About" editing, which could not have worked in the shipped
  build: the native core still called the old whatsmeow status API by value while
  the dependency had moved to a pointer-bearing input struct, where the pointer
  is what distinguishes "set this" from "leave it alone". Updated whatsmeow to
  `0dcf1f50f4b1` and rebuilt the bundled native archive.
- Closed six vulnerabilities that were reachable from the native core compiled
  into the APK, rather than merely present in something it depends on: a VP8L
  decoding flaw in `golang.org/x/image` reached through outgoing image
  preparation, and five Go standard-library issues in `crypto/tls`, `net/url`,
  `net/http`, `encoding/asn1` and IDNA handling reached through the bridge's
  server and its media uploads. `golang.org/x/image` moves to v0.45.0 and the
  build is pinned to Go 1.25.13, which carries the patched library. `govulncheck`
  now runs in the release gate and is allowed to fail it: it reports only
  advisories whose affected symbols this code can actually reach, so a hit there
  is a finding rather than noise.
- Bounded a backup import as a whole, not just one file at a time. Each entry was
  capped at 512 MB, which fifty entries or a hundred thousand tiny ones satisfy
  individually while together filling the cache partition before anything is
  committed. Extraction now spends a single budget across the archive, in bytes
  and in entry count, charged while the bytes are being written and counted
  before an entry's name is examined so padding with ignored entries is bounded
  too. The sizes declared inside the zip are still never trusted.
- Put dependency updates on a schedule instead of on nobody: Dependabot now
  watches the Go module, the Gradle version catalog, and the SHA-pinned GitHub
  Actions, whose pins are exactly what makes an upstream change invisible without
  it.
- Removed the operator's real WhatsApp display name from a chat-import test
  fixture, where it had been pasted verbatim out of a genuine export, and added a
  release-hygiene gate that refuses to let it back in. The terms it screens for
  are kept in a gitignored file rather than the tracked script, because writing
  them into the script would publish precisely what the gate exists to prevent;
  failures report the file and line only, never the matched text, since a CI log
  is as public as the repository.

## 1.0.2 — 2026-08-14

- Made a policy-deferred reply wake up when the limit is actually lifted. It slept
  in one stretch until the deadline it was given, but that deadline is a
  prediction made under the caps as they stood — switching the warm-up off raises
  the hourly budget on the spot, and the turn slept through it with room to spare.
  The only way to get an answer was to send another message. It now waits in
  bounded slices and re-runs the real check, so a lifted limit is answered within
  a minute and a standing one simply defers again.
- Stopped the "Limit reached" row from outliving the limit. It has no dismiss
  control by design, on the grounds that a rule still in force must not be
  hideable — but nothing ever withdrew it: a send that succeeded returned without
  clearing it, no one re-evaluated it at the deadline it printed, and changing the
  setting that lifted the cap left it untouched. It now clears when a send gets
  through, clears on a settings change, and is not drawn once its own deadline has
  passed.
- Took the "generating memory" indicator down when the memory is written rather
  than when the whole cycle ends. It was cleared in a `finally` that also wrapped
  the persona synthesis — a second full model call after the commit — so a chat
  memory that was finished and durable still read as in progress for minutes.
- Stopped killing long provider answers. The per-attempt deadline was documented
  as a limit on *silence* but implemented as a limit on *duration*: it wrapped the
  whole streamed read, and the chat client also carried a total OkHttp call
  timeout. Both fired soonest on the longest answers, so a memory write
  condensing a few thousand messages was abandoned after the provider had already
  generated and billed it. The deadline now restarts whenever bytes arrive, the
  chat client keeps only its between-bytes timeout, and a stream that keeps
  producing runs to completion however long it takes.
- Fixed a chat's memory being written but never shown. The chat screen resolved a
  per-contact persona with a plain map lookup, which cannot work for a chat keyed
  by LID: the override is filed under the phone number that was typed, the digits
  do not match, and the screen quietly fell back to the global persona. It then
  read messages, memory and the scheduled follow-up under a conversation key
  nothing had ever been stored against. It now resolves the persona through the
  same alias-aware path the engine uses, and looks for memory under the same
  candidate keys.
- Closed the remaining publication questions. Bundled persona images are now
  recorded with their actual generator and terms basis instead of an open owner
  question, and every one was verified to carry no EXIF, XMP, IPTC, C2PA, GPS,
  author or path metadata.
- Disclosed the optional direct transcription route in `PRIVACY.md` and
  `docs/SECURITY.md`: with a transcription endpoint configured, voice-note audio
  leaves the phone to that endpoint under its own credential rather than through
  OpenRouter. The client's own contract comment described only one of its two
  routes and now describes both.
- Made the ignore rules recursive for `.env` files and taught both `.gitignore`
  and the hygiene gate to reject the in-app `whatsapp-bot-backup-*.zip` export,
  which carries the runtime database and approved media.
- Added a private vulnerability reporting route to `docs/SECURITY.md`.
- Completed the minimum public-source release audit. Persistent transport
  diagnostics now drop block-list identities and raw exception messages; the
  native logger redacts common identifiers, credentials, pairing codes, and
  personal paths and no longer prints panic payloads or stacks.
- Hardened recursive ignore and tracked-file/archive hygiene checks without ever
  echoing a matched secret value. Removed generated daemon criteria and made
  JDK 21 explicit for local/CI Gradle while retaining Java 17 compatibility.
- Reconstructed the README from current source: exact Android identity, models,
  memory cadence, architecture, permissions, intentional `allow_all=true`, data
  flows, export sensitivity, arm64 limit, signing path, and real notification
  behavior are now explicit.
- Added a public-release gate document that distinguishes the clean Android tree
  and dedicated GitHub history from the unsafe legacy parent history, records
  the remaining asset/signing owner decisions, and gives the first-release
  procedure.
- Pinned GitHub Actions to immutable commits and made the tag workflow rerun
  hygiene, Go test/vet, Android tests, and lint before signing. It now enforces
  tag/version agreement and keeps certificate details out of job logs.
- Made every notification row on the chat list open the thing that fixes it. The
  WhatsApp link and API-key rows drew a chevron and did nothing, because they
  named settings that deliberately have no row of their own: the settings page
  now resolves shortcut destinations as well as setting keys, and a row without
  an exact destination still opens Settings instead of swallowing the tap.
- Pointed provider errors other than a rejected key at the activity log rather
  than at the API-key panel, which was never the repair.
- Put the launcher icon on white with a light shadow, replacing the synthwave
  grid, and softened the mark's glow and cast shadow to suit a light ground.
- Corrected the README on where WhatsApp credentials actually live, on which
  Gradle task produces a distributable APK, and on the arguments the native
  build script requires. Removed the screenshot table, which referenced six
  images that were never committed.
- Corrected the claim in `docs/SECURITY.md` that the native core redacts its
  logs. It has no logger of its own; it forwards whatsmeow warnings.
- Pinned `gomobile` to the `x/mobile` revision the module already requires, so
  the checked-in native AAR cannot be regenerated by a different toolchain.
- Split CI back into separate test and lint invocations, reversing the 1.0.1
  change: lint's worker starves a unit-test task sharing the same build, which
  is exactly what the README tells contributors not to do.
- Corrected the OpenRouter attribution URL, which pointed at a repository path
  that does not exist.
- Added a tag-triggered release workflow that builds the signed APK and attaches
  it, with its SHA-256, to the GitHub release. The README has always pointed at a
  releases page; nothing was ever published to it. The workflow refuses to upload
  an APK whose certificate says `CN=Android Debug`, because the debug key is
  public and an update signed with it would be forgeable by anyone.
- Documented how to create the signing key, what is lost if it is lost, and the
  four repository secrets the release workflow reads.

## 1.0.1 — 2026-08-13

- Reconciled successful WhatsApp pairing with onboarding so a transient post-pair
  stream restart cannot leave a linked device behind an abandoned-operation error.
- Rebuilt first-run setup in the requested order, with the pairing code directly
  below Send code, a green connected state, 500 ms protected key autosave feedback,
  universal Finish setup, and optional admin/battery controls under Extended settings.
- Made missing WhatsApp/OpenRouter conditions non-dismissible and fixed credential
  changes so the API-key warning clears from the same Keystore truth used by Settings.
- Increased nested-panel scroll handoff to one eighth of the screen.
- Changed the default image-generation model to `openai/gpt-image-2`.
- Added in-app third-party notices, exact native license texts, an explicit unchanged-
  whatsmeow declaration, and a GPL review warning for the linked libsignal module.
- Relicensed the app source from Apache-2.0 to GPL-3.0-only so the combined native
  release has terms compatible with the statically linked libsignal implementation.
- Fixed the GitHub release-hygiene step returning failure after a successful
  no-secret scan, moved the release gate off the slow Windows runner, and collapsed
  Android test, lint, and assembly into one Gradle invocation.

## 1.0.0 — 2026-08-13

- Prepared the Android-only product for an Apache-2.0 source release and renamed
  its visible label to Doppel. Persona image assets retain a separate
  documented provenance boundary.
- Reworked first-run pairing so one Send code tap waits for bridge readiness;
  reordered Telephone number, Send code, API key, and optional admin number;
  added code-only clipboard copy and the correct WhatsApp Linked devices path.
- Added the matching Battery usage recommendation and Android App info shortcut.
- Defaulted fresh installs to all direct contacts and groups, Instant replies,
  Default battery mode, reboot start, all media handling, image generation, and
  a 20 MiB video cap.
- Set DeepSeek V4 Pro 0813/high for replies, DeepSeek V4 Flash 0731/low for
  verification, and Gemini 3.5 Flash Lite/minimal for media reasoning.
- Added one shared Allow every person setting to the chat plus menu and Settings,
  with the existing allowlist revealed when it is disabled.
- Improved safety-notice dismiss controls and parent/child settings scrolling.
- Hardened GPT Image 2 request encoding and HTTP 400 diagnostics.
- Replaced the launcher robot with a green/white chat-pulse adaptive icon.
- Removed local private artifacts and personal build paths, hardened repository
  hygiene, corrected/bundled third-party notices, and separated diagnostic from
  non-debuggable release builds.
