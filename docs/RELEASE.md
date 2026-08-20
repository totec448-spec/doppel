# Public release gate

This document is the evidence-backed gate for the first public source release
and later APK releases. It describes the Android repository rooted at this
directory. The retired parent TypeScript project is not part of this product.

## Release identity

- Repository: `totec448-spec/doppel`
- Application ID and namespace: `de.totec.doppel`
- App label: `Doppel`
- Version: `1.0.0` (`versionCode` 1)
- Minimum Android: 9 / API 28
- Target SDK: 36; compile SDK: 37
- Bundled ABI: arm64-v8a only
- Repository license: GPL-3.0-only
- Release tag format: `vMAJOR.MINOR.PATCH`; the tag must match `versionName`

## Audit result

The current Android tree was inspected across source, resources, Gradle files,
manifest and backup policy, workflows, documentation, Git metadata, bundled
assets, licenses, and binary archives.

Current-tree findings:

- No API key, token, password, private key, keystore, runtime database, chat
  export, diagnostic dump, APK, AAB, or private screenshot is known to be
  tracked.
- The release-hygiene script scans tracked text and the contents of bundled
  archives. Its failure output reports only locations, never matched secret
  values.
- The large tracked binary is the intentional arm64 `app/libs/nativewa.aar`.
- Android identity, manifest component authorities, network policy, and
  version metadata are consistent with `de.totec.doppel`.
- Backups and device-transfer extraction are disabled. Cleartext is disabled
  except for the authenticated loopback bridge.
- Distribution signing requires four environment variables and does not store
  a signing key or password in Git. Plain `assembleRelease` is debug-signed when
  those variables are absent and must never be distributed.
- The repository contains GPL-3.0-only application terms, third-party notices,
  upstream license texts, and an in-app notice surface.

Implemented release fixes:

- Block-list JIDs are removed before safety frames enter long-lived activity or
  snapshot JSON.
- Persistent bridge errors and Android logcat use bounded error types rather
  than raw exception messages or stack traces.
- The native logger redacts common JIDs, phone-like values, bearer/API-key
  shapes, pairing codes, and personal paths; panic payloads and stacks are no
  longer logged.
- Pairing is a full recovery edge: a new request replaces a terminally parked
  native runtime, and a single-flight gate prevents repeated taps from queueing
  multiple reconnects.
- The repository GPL appendix now identifies Doppel and its contributors; the
  upstream libsignal GPL copy remains separately preserved under `LICENSES/`.
- Ignore and hygiene rules cover nested runtime databases, logs, backups,
  signing material, APK/AAB artifacts, and environment files.
- GitHub Actions are pinned to immutable commits. Both the regular and tagged
  release gates run hygiene, Go tests/vet, Android unit tests, and lint before a
  publishable build; the release workflow also verifies tag/version agreement
  and rejects a debug certificate without printing certificate details.
- The generated Gradle daemon-JVM criteria file was removed; JDK 21 is now the
  explicit local/CI Gradle runtime while source and bytecode compatibility stay
  on Java 17.
- README, privacy, security, asset, setup, notice, and release documentation now
  describe the implemented behavior and unresolved decisions.

## Git history boundary

There are two different claims:

1. **Current Android tree:** no known committed private artifact remains in the
   tree after the release-hygiene scan.
2. **Legacy parent history:** not safe to publish. It contains a removed private
   conversation screenshot, personal workstation paths, personal commit-author
   email addresses, retired application material, and historical generated
   native archives.

The dedicated Android GitHub repository was intentionally created as a clean
root history and does not inherit the legacy parent commits. Continue that
history. Never mirror, force-push, merge, graft, or push the parent repository's
branch or tags into the Android repository.

No history rewrite is required for the dedicated Android repository based on
the present audit. If the legacy parent repository ever needs publication, do
not try to remove only the one known screenshot. Create a fresh filtered clone,
retain only the Android subtree, rename it to the repository root, run a full
secret/history scan, review author metadata, and publish to a new remote:

```bash
git clone --no-local <legacy-parent> sanitized-android
cd sanitized-android
git filter-repo --path phone-app/ --path-rename phone-app/: --force
git filter-repo --path docs/evidence/live-phone-conversation-2026-08-01.png --invert-paths --force
git log --all --stat
pwsh ./scripts/verify-release-hygiene.ps1
```

That process is destructive to commit IDs and must be done only in a disposable
clone. The second filter removes the known historical screenshot but does not
prove that every older commit is clean; an all-history secret scan and author
metadata review are still mandatory. The existing dedicated Android repository
is the shorter and safer path.

## Remaining owner decisions

These cannot be inferred safely from source:

1. ~~Confirm the generation-provider output terms for every bundled persona
   image.~~ Resolved: the images were generated by the owner from text prompts
   with OpenAI's GPT Image 2, with no third-party reference image supplied.
   `ASSET_LICENSE.md` now records that as the provenance rather than as an open
   question.
2. ~~Create and back up the long-lived Android release keystore, then configure
   the four GitHub Actions secrets.~~ Resolved on 2026-08-20: a new Doppel-only
   RSA-4096 keystore exists outside the repository, all four Actions secrets are
   set on `totec448-spec/doppel`, and `RELEASE_CERT_SHA256` pins its certificate.
   The local recovery bundle is stored outside the repository and must remain
   private and backed up. Losing it prevents in-place upgrades; exposing it lets
   someone sign a malicious update. GitHub cannot reveal secret values later.
3. Change the dedicated GitHub repository from private to public only when the
   source tree and this gate are accepted. Source must be available no later
   than distribution of a GPL-covered APK.

The initial `allow_all=true` behavior is intentional and is not a release
blocker. It is disclosed prominently in setup documentation. Operators who want
a closed installation must disable it and configure allowlists before pairing.

## Verification record

The public-source preparation pass deliberately does not create, install, tag,
or upload an APK. Before committing, run these as separate gates:

```powershell
./scripts/verify-release-hygiene.ps1
Set-Location native-wa
go test ./...
go vet ./...
Set-Location ..
./gradlew.bat --no-daemon :app:testDebugUnitTest
./gradlew.bat --no-daemon :app:lintDebug :app:lintRelease
```

Results for the 2026-08-14 public-source preparation pass:

- Go `test ./...`: pass
- Go `vet ./...`: pass
- Native arm64 AAR regeneration: pass
- Android `:app:testDebugUnitTest`: pass
- Android `:app:lintDebug :app:lintRelease`: pass
- Release hygiene: pass (351 tracked files scanned; 36 bundled product images
  preserved). The hygiene script counts those images rather than decoding them,
  so their contents were checked separately: each of the 36 was read for EXIF,
  XMP, IPTC, C2PA, GPS, author, software and embedded-path markers, and none
  carried any.
- APK assemble/install/upload: intentionally not run at the owner's request

Results for the 2026-08-16 pass, run before the first push to the dedicated
repository:

- Go `test -count=1 ./...`: pass (uncached, 2.0s)
- Go `vet ./...`: pass
- Android `:app:testDebugUnitTest --rerun`: pass (690 tests in 79 classes, no
  failures, no skips)
- Android `:app:lintDebug :app:lintRelease`: pass
- Release hygiene: pass (355 tracked files scanned; 36 bundled product images
  preserved)
- Native AAR: not rebuilt in this pass. The committed archive is the one from
  2026-08-14 and no file under `native-wa/` changed since, so the source and the
  binary still correspond. The release workflow rebuilds it from the checkout
  regardless.
- APK assemble/install/upload: not run

Results for the 2026-08-20 release-candidate pass:

- Release hygiene: pass (354 tracked files scanned; 36 bundled product images
  preserved).
- Android `:app:testDebugUnitTest --rerun-tasks`: pass (691 tests in 80 classes,
  no failures and no skips).
- Android `:app:lintDebug :app:lintRelease`: pass.
- Android `:app:assembleRelease --rerun-tasks`: pass; the minified local
  candidate verified under APK Signature Scheme v2 and was correctly identified
  as debug-signed, so it is not the distributable artifact.
- The focused `BotDatabaseMigrationTest` was built but did not execute: MIUI
  rejected installation of the test APK with `INSTALL_FAILED_USER_RESTRICTED`
  after the device-side install was cancelled. This is an unexecuted gate, not a
  test failure; the release change does not alter the database schema.
- Two data-preserving installs of the local release candidate were rejected by
  the same device policy before package replacement, so no claim of final
  on-device installation is made for this pass.
- A new Doppel-only RSA-4096 distribution key was generated after this local
  candidate pass. The protected tag workflow remains the authority for the
  published APK and verifies its certificate against the repository pin.

`go test` needs a real Go SDK on `GOROOT`. The `go.exe` inside this repository
is trimmed and fails with "cannot find GOROOT directory" when invoked from
`PATH` alone, which reads like a broken module and is not one. Point `GOROOT` at
a full 1.25.13 SDK first.

A source/lint pass does not prove live provider, WhatsApp, battery, or device
behavior.

## GitHub release publication

The repository starts from one clean Doppel root commit with no inherited Git
history. The obsolete `whatsapp-ai-bridge-for-android` repository and the first
private Doppel staging repository were deleted before this publication.

Every version tag must pass the protected workflow: source hygiene, native
test/vet/vulnerability checks, a fresh arm64 native rebuild, all 691 Android JVM
tests, both lint variants, the minified distribution build, and a single signing
certificate matched against the repository pin. The separate API-30 managed-
device workflow must also pass the sparse-v1 database migration before a tag is
published.

For later releases, confirm the version and changelog, rerun the local gates,
push `main`, and push a matching annotated version tag. The tag workflow remains
the sole authority for protected signing and release publication. Device
installation is intentionally left to the owner.

Never commit the APK, keystore, signing passwords, generated export, or release
secrets to the source tree.
