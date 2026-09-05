param([Parameter(Mandatory = $true)][string]$GoRoot)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$go = Join-Path $GoRoot 'bin/go.exe'
$env:GOROOT = $GoRoot
Push-Location (Join-Path $project 'native-wa')
try {
    $modules = & $go list -deps -f '{{with .Module}}{{.Path}}|{{.Version}}|{{.Dir}}{{end}}' .
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve native dependency licenses' }
    # gomobile links its runtime into the generated binding, outside the app import graph.
    $modules += & $go list -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' golang.org/x/mobile
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve mobile runtime license' }
    $text = [Text.StringBuilder]::new()
    [void]$text.AppendLine("Native permissive dependency license texts`n==========================================`n")
    [void]$text.AppendLine("===== Go standard library =====`n")
    [void]$text.AppendLine([IO.File]::ReadAllText((Join-Path $GoRoot 'LICENSE')).Trim())
    foreach ($module in ($modules | Where-Object { $_ } | Sort-Object -Unique)) {
        $parts = $module -split '\|'
        if ($parts[0] -in @('de.totec.doppel/nativewa', 'go.mau.fi/whatsmeow', 'go.mau.fi/util', 'go.mau.fi/libsignal')) { continue }
        # MPL/GPL components have their full texts in the separate license files.
        $licenses = @(Get-ChildItem -LiteralPath $parts[2] -File | Where-Object Name -Match '^(LICENSE|COPYING)')
        if (!$licenses.Count) { throw "Missing license for $($parts[0])" }
        [void]$text.AppendLine("`n===== $($parts[0]) $($parts[1]) =====`n")
        foreach ($license in $licenses) { [void]$text.AppendLine([IO.File]::ReadAllText($license.FullName).Trim()) }
    }
    [IO.File]::WriteAllText((Join-Path $project 'LICENSES/NATIVE_PERMISSIVE_LICENSES.txt'), $text.ToString())
} finally { Pop-Location }
