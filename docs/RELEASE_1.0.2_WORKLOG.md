# Doppel 1.0.2 work log

## Scope and baseline

Requested: improve sending durability, make DeepSeek V4 Flash Vision the first-run
default with automatic provider selection, refresh the UI, fix discovered bugs,
update dependencies and publish 1.0.2. Provider routing was explicitly clarified.
The baseline was clean at `2ed3123`; GitHub had only the public `v1.0.0` release.
Older 1.0.1/1.0.2 changelog entries describe the predecessor application's version
line. Doppel 1.0.2 uses versionCode 3. No private runtime records were inspected.

## Investigation and implementation

- Read settings, model routing, OpenRouter streaming/retries, Android outbox and
  native action journal, repository leasing, UI, build and publication workflows.
- Outbox: last-waiter removal raced new registration. Serialize departure against
  registration, and also remove unneeded waiters on completed/dead fast paths.
- Request identities: replacement of unsupported characters was lossy. Hash the
  original key when normalization changes it; existing wire-safe IDs stay stable.
  Persisted outbox rows continue replaying their stored ID unchanged.
- Streams: EOF was treated as success and JSON null became a literal "null"
  finish reason. Require DONE or a real finish reason. Truncated/broken streams
  use the existing single protocol-retry budget; partial tools are never returned.
  A regenerated completion can incur a second provider charge.
- Native send preflight: upstream SendMessage returns ErrNotLoggedIn,
  ErrClientIsNil and ErrRecipientADJID before sending. These must not be marked
  wire-contacted or poison the journal reservation. Ambiguous sends, timeouts and
  account restrictions retain their existing conservative treatment.
- New-install defaults change through the settings schema; persisted choices are
  retained. Rename the provider switch to describe its actual exclusive behavior.
- UI: saved conversation search, explicit no-match state, two-line previews with
  a separate timestamp column, increased row spacing and secondary text contrast.
- Native build: reinstall both gomobile and gobind at the module pin instead of
  trusting an existing executable. Update upstream source/license references.

## Dependency discovery

Checked official Google Maven/Maven Central metadata, Gradle's current-release
API, Go's release API, the Go module proxy and GitHub release/commit APIs.
Updated stable releases only; retained already-current AndroidX and JUnit pins.
Exact resolved versions are in the version catalog and Go module files; immutable
GitHub Actions commits are in the workflows.

- Model: https://openrouter.ai/deepseek/deepseek-v4-flash-vision-exp
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Kotlin: https://kotlinlang.org/docs/releases.html
- Gradle: https://services.gradle.org/versions/current
- Go: https://go.dev/dl/

## Validation

Validation results and publication evidence are recorded below as gates finish.
Unit tests/builds do not establish live WhatsApp delivery, provider responses,
phone background survival, or visual device behavior. No live user chats are used
as test traffic.

- Native tests and vet passed on Go 1.27.1. govulncheck 1.7.0 found no
  reachable vulnerabilities (one advisory in an unused required-module symbol).
- Rebuilt the arm64 AAR with Go 1.27.1 and the pinned mobile generators.
- Regenerated the complete permissive license bundle from resolved source licenses
  using scripts/update-native-notices.ps1, including the generated mobile runtime.
- First Android suite exposed an existing startup race in RuntimeRestartPolicyTest:
  cancellation could precede the body, so its finally signal never arrived. A
  thread dump located the wait; the test now awaits startup and bounds both waits.
  Terminated only that stuck test worker and started a fresh full suite.
- Verified the exact Flash Vision model ID in the live OpenRouter model catalog.
- Full Android suite passed: 695 tests, zero failures/errors/skips. Added a final
  broken-socket/DONE-boundary regression afterward for the final run.
- Lint initially crashed in FIR analysis of a test source. A fresh unchanged-source
  invocation passed both debug and release lint without disabling any checks.
- Minified release assembly passed on the updated toolchain. This local artifact
  uses the local test signature; distribution signing is owned by GitHub Actions.
- Verified rebuilt AAR metadata: Go 1.27.1, whatsmeow 28bfe537ea6a, SQLite 1.14.50,
  x/crypto 0.56.0, and x/mobile 4776eadac327.
- Fixed migration CI license acceptance so yes/SIGPIPE cannot override a successful
  sdkmanager result. Updated govulncheck to the tested 1.7.0 pin.
- Regenerated the complete Gradle 9.7.1 wrapper with its verified distribution hash.
- The 696-test run exposed a second existing nondeterministic fixture: the optional
  typo-edit test used a word containing adjacent identical letters; swapping them
  correctly produces no edit. Replaced the fixture with a word whose adjacent
  letters differ, so the test consistently exercises edit rejection.
- Both lint variants passed with zero errors and 11 warnings (existing target SDK,
  parameter ordering, wake-lock lifecycle and style suggestions). Target SDK stays
  36; a platform behavior migration is separate from dependency upgrades.
- Final complete Android suite: 696 tests passed, zero failures/errors/skips.
- Final staged hygiene: 358 files scanned, 36 product images preserved. Diff
  whitespace validation passed. GitHub main has no intervening source commits.
- Local verification complete; the source commit is pushed for the branch and
  managed-device migration gates before tagging the signed distribution release.
