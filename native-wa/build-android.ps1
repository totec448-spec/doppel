param(
    [Parameter(Mandatory = $true)]
    [string]$GoRoot,
    [Parameter(Mandatory = $true)]
    [string]$AndroidNdkHome,
    [string]$Output = "..\app\libs\nativewa.aar",
    [string]$Architecture = "arm64",
    [int]$AndroidApi = 28,
    [string]$SanitizedBuildRoot = "$env:PUBLIC\doppel-native-build"
)

$ErrorActionPreference = "Stop"
$resolvedGo = (Resolve-Path -LiteralPath $GoRoot).Path
$resolvedNdk = (Resolve-Path -LiteralPath $AndroidNdkHome).Path
$sourceModuleRoot = $PSScriptRoot
$projectRoot = Split-Path -Parent $sourceModuleRoot
$outputPath = if ([IO.Path]::IsPathRooted($Output)) {
    [IO.Path]::GetFullPath($Output)
} else {
    [IO.Path]::GetFullPath((Join-Path $sourceModuleRoot $Output))
}

# gomobile creates a temporary module with a local replace directive and Go records
# that replacement path in .go.buildinfo even with -trimpath. Building straight from
# a user profile therefore leaks the operator's home path into libgojni.so. Stage only
# the module source under the shared Public profile so the binary remains reproducible
# and contains no personal build path.
$expectedBuildRoot = [IO.Path]::GetFullPath("$env:PUBLIC\doppel-native-build")
$resolvedBuildRoot = [IO.Path]::GetFullPath($SanitizedBuildRoot)
if (-not $resolvedBuildRoot.Equals($expectedBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "SanitizedBuildRoot must resolve to $expectedBuildRoot"
}
if (Test-Path -LiteralPath $resolvedBuildRoot) {
    Remove-Item -LiteralPath $resolvedBuildRoot -Recurse -Force
}
$moduleRoot = Join-Path $resolvedBuildRoot "native-wa"
New-Item -ItemType Directory -Force -Path $moduleRoot | Out-Null
Get-ChildItem -LiteralPath $sourceModuleRoot -File |
    Where-Object { $_.Extension -eq ".go" -or $_.Name -in @("go.mod", "go.sum") } |
    Copy-Item -Destination $moduleRoot

$env:GOROOT = $resolvedGo
$env:ANDROID_NDK_HOME = $resolvedNdk
# gomobile shells out to whatever `go` PATH resolves first, and this repository ships
# trimmed go.exe copies (bin\, .go-tools\) that are found before any installed SDK.
# The driver then drives a GOROOT it does not match and every package fails with
# "compile: version ... does not match go tool version ...". Put this GOROOT first.
$env:PATH = (Join-Path $resolvedGo "bin") + [IO.Path]::PathSeparator + $env:PATH
$env:GOPATH = Join-Path $projectRoot ".gopath"
$env:GOMODCACHE = Join-Path $projectRoot ".go-module-cache"
$env:GOCACHE = Join-Path $projectRoot ".go-build-cache"
$env:GOFLAGS = "-buildvcs=false"
$env:CGO_LDFLAGS = "-Wl,-z,max-page-size=16384"
$env:TEMP = Join-Path $resolvedBuildRoot ".tmp"
$env:TMP = $env:TEMP

New-Item -ItemType Directory -Force -Path $env:GOPATH, $env:GOMODCACHE, $env:GOCACHE, $env:TEMP | Out-Null
# Install both generators from the module pin even when an older binary is present.
# A cached gomobile/gobind pair must not silently survive a dependency update.
$pinnedMobile = Select-String -LiteralPath (Join-Path $sourceModuleRoot "go.mod") -Pattern '^\s*golang\.org/x/mobile\s+(v\S+)' | Select-Object -First 1
if (-not $pinnedMobile) { throw "golang.org/x/mobile is not pinned" }
$mobileVersion = $pinnedMobile.Matches[0].Groups[1].Value
foreach ($tool in @("gomobile", "gobind")) {
    & (Join-Path $resolvedGo "bin\go.exe") install "golang.org/x/mobile/cmd/${tool}@$mobileVersion"
    if ($LASTEXITCODE -ne 0) { throw "go install $tool failed with exit code $LASTEXITCODE" }
}
$env:PATH = (Join-Path $env:GOPATH "bin") + [IO.Path]::PathSeparator + $env:PATH
$gomobile = Join-Path $env:GOPATH "bin\gomobile.exe"
Push-Location $moduleRoot
try {
    & $gomobile bind `
        "-target=android/$Architecture" `
        "-androidapi=$AndroidApi" `
        -trimpath `
        "-ldflags=-s -w" `
        -o $outputPath `
        .
    if ($LASTEXITCODE -ne 0) {
        throw "gomobile bind failed with exit code $LASTEXITCODE"
    }
    # gomobile emits a Java source sidecar next to the AAR. The app compiles against classes.jar
    # inside the AAR, so retaining the sidecar only creates a stale second generated artifact.
    $sourcesJar = Join-Path ([IO.Path]::GetDirectoryName($outputPath)) `
        ([IO.Path]::GetFileNameWithoutExtension($outputPath) + "-sources.jar")
    if (Test-Path -LiteralPath $sourcesJar) {
        Remove-Item -LiteralPath $sourcesJar -Force
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $resolvedBuildRoot) {
        Remove-Item -LiteralPath $resolvedBuildRoot -Recurse -Force
    }
}
