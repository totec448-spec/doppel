# Third-party notices

The Android APK contains third-party open-source software. This notice is copied
into every APK as `assets/THIRD_PARTY_NOTICES.md`. Exact Android versions are
pinned in `gradle/libs.versions.toml`; exact native versions and integrity hashes
are pinned in `native-wa/go.mod` and `native-wa/go.sum`.

## whatsmeow

- Project: https://github.com/tulir/whatsmeow
- Package: `go.mau.fi/whatsmeow`
- Pinned revision:
  `v0.0.0-20260814123134-0dcf1f50f4b1`
- License: Mozilla Public License 2.0
- Exact upstream source:
  https://github.com/tulir/whatsmeow/tree/0dcf1f50f4b1
- Upstream license:
  https://github.com/tulir/whatsmeow/blob/0dcf1f50f4b1/LICENSE

The upstream whatsmeow package is consumed as a dependency without source
modification or a local `replace` directive. The application-specific glue,
authenticated loopback protocol, persistence, bounded media handling, safety
projection, power control and action mapping are separate files in `native-wa/`.
The integration differences are documented in `docs/WHATSMEOW_INTEGRATION.md`.
No upstream whatsmeow source file is patched, forked, vendored or replaced. The
documented changes are app-owned adapter behavior around the public API, not
undisclosed modifications of MPL-covered files.

## Android and Kotlin runtime components

- AndroidX Core, Lifecycle, Activity, Compose UI and Material 3 — Apache License
  2.0 — https://github.com/androidx/androidx
- Kotlin standard/runtime components and kotlinx.coroutines — Apache License 2.0
  — https://github.com/JetBrains/kotlin and
  https://github.com/Kotlin/kotlinx.coroutines
- OkHttp and Okio — Apache License 2.0 —
  https://github.com/square/okhttp and https://github.com/square/okio

Test-only JUnit, JSON-java, AndroidX Test, Espresso and MockWebServer components do not ship
in the release APK, but remain governed by their upstream licenses.

## Native runtime components

The shipped ARM64 `libgojni.so` build metadata was inspected with `go version
-m`. It contains the Go standard library and the exact modules recorded by the
binary, including coder/websocket, mattn/go-sqlite3, Google Protocol Buffers,
go.mau.fi/util, whatsmeow and libsignal. The reproducible dependency pins and
checksums are in `native-wa/go.mod` and `native-wa/go.sum`.

`modernc.org/sqlite` appears in `native-wa/go.mod` as a direct requirement and
is deliberately absent from the shipped-binary license list. It is imported
only by `journal_test.go`, as a second, driver-independent SQLite implementation
for tests; shipped code opens SQLite through `github.com/mattn/go-sqlite3`.
It is therefore not linked into `libgojni.so` and does not appear in that
binary's build metadata.

The complete copyright and license texts for every permissively licensed module
recorded in that binary are shipped as
`assets/licenses/NATIVE_PERMISSIVE_LICENSES.txt`. That file covers the Go
standard library; edwards25519; argo-go; coder/websocket; orderedmap; uuid;
go-colorable; go-isatty; go-sqlite3; goid; zerolog; gqlparser; the linked
golang.org/x modules; and Google Protocol Buffers at their exact build versions.

### libsignal-protocol-go

- Package: `go.mau.fi/libsignal`
- Pinned version: `v0.2.2`
- License: GNU General Public License version 3.0
- Exact source archive:
  https://proxy.golang.org/go.mau.fi/libsignal/@v/v0.2.2.zip
- Package source browser: https://pkg.go.dev/go.mau.fi/libsignal@v0.2.2

The exact GPL-3.0 text is shipped as `assets/licenses/GPL-3.0-only.txt`. Because
this package is linked into the native executable, the app source and combined
release are licensed under GPL-3.0-only. Public APK distribution must include
compliant Corresponding Source. The complete app-owned source and build scripts
are intended to be available at the repository URL shown in the app; a private
repository does not satisfy source availability for a public binary.

### MPL components

`go.mau.fi/whatsmeow` and `go.mau.fi/util` are MPL-2.0 components. The exact
license text is shipped as `assets/licenses/MPL-2.0.txt`. Their pinned source is
available through their module versions and the whatsmeow commit link above.

There is no FFmpeg executable or library in the APK. Voice-note encoding uses
Android MediaCodec plus the app-owned streaming PCM DSP.

The MPL-covered whatsmeow source is available at the exact commit link above.
No upstream whatsmeow source file is copied into or modified by this repository.
The Android/Kotlin application and the separately authored `native-wa/` adapter
are not represented as part of the whatsmeow project.

Because the combined work is GPL-3.0-only, a published binary has to be
accompanied by the corresponding source of that exact build. This repository is
that source, and every release is tagged with the commit the APK was built from.
Publishing an APK while the repository is private does not satisfy the licence.

The bundled persona images are covered separately in `ASSET_LICENSE.md`.
