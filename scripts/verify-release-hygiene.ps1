[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gitPrefix = (& git -C $projectRoot rev-parse --show-prefix).Trim().TrimEnd('/')
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to resolve the project path inside Git.'
}
$trackedFiles = @(
    & git -C $projectRoot ls-files -- . |
        ForEach-Object {
            if ($gitPrefix -and $_.StartsWith("$gitPrefix/")) {
                $_.Substring($gitPrefix.Length + 1)
            } else {
                $_
            }
        } |
        Where-Object { Test-Path -LiteralPath (Join-Path $projectRoot $_) }
)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to enumerate tracked release files.'
}

$privateArtifacts = @(
    $trackedFiles | Where-Object {
        $_ -match '(?i)\.(db|db-wal|db-shm|sqlite|sqlite-wal|sqlite-shm|log|apk|aab|apks|idsig|bak|backup|jks|keystore|p12|pem|key|cer|crt|der)$' -or
        $_ -match '(?i)(^|/)\.env(?:\..+)?$' -or
        # The in-app export bundles the runtime database and approved media.
        $_ -match '(?i)(^|/)whatsapp-bot-backup-.*\.zip$' -or
        $_ -match '(?i)^(data|chat-exports|captures)/' -or
        $_ -match '(?i)(^|/)screenshots?/' -or
        (
            $_ -match '(?i)\.(png|jpg|jpeg|webp)$' -and
            $_ -notmatch '^app/src/main/(assets|res)/'
        )
    }
)
if ($privateArtifacts.Count -gt 0) {
    throw "Tracked private or non-product artifacts found:`n$($privateArtifacts -join "`n")"
}

$sensitivePattern = 'C:\\Users\\|C:/Users/|/C:/Users/|(^|[^A-Z0-9])[A-Z][A-Z0-9]{15}([^A-Z0-9]|$)|sk-(or-v1-|proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[A-Za-z0-9_-]{30,}|xox[baprs]-[A-Za-z0-9-]{20,}|Bearer[[:space:]]+[A-Za-z0-9._~+/=-]{20,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
$sensitiveMatches = @(
    & git -C $projectRoot grep -I -n -E -- $sensitivePattern -- . `
        ':(exclude)scripts/verify-release-hygiene.ps1' 2>$null
)
$grepExitCode = $LASTEXITCODE
if ($grepExitCode -eq 0) {
    $sensitiveLocations = @(
        $sensitiveMatches | ForEach-Object {
            $parts = $_ -split ':', 3
            if ($parts.Count -ge 2) { "$($parts[0]):$($parts[1])" } else { $parts[0] }
        } | Sort-Object -Unique
    )
    throw "Tracked personal paths or secret-shaped material found at:`n$($sensitiveLocations -join "`n")"
}
if ($grepExitCode -ne 1) {
    throw "Release hygiene content scan failed with exit code $grepExitCode."
}

# git grep intentionally skips binary archives. Inspect the checked-in native AAR
# entry-by-entry so gomobile build metadata cannot reintroduce a personal build
# path or key material into the APK through libgojni.so.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$binarySensitivePatterns = @(
    'C:\\Users\\(?!Public\\)',
    'C:/Users/(?!Public/)',
    '/C:/Users/(?!Public/)',
    'sk-or-v1-[A-Za-z0-9_-]{20,}',
    'sk-proj-[A-Za-z0-9_-]{20,}',
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
)
# Operator identity — the real display name, the private mail address — must never reach the
# published tree. The terms to screen for are themselves the thing being protected, so writing
# them into this tracked script would publish exactly what the gate exists to stop. They live in
# a gitignored file instead: the check runs wherever that file exists and the names never enter
# the repository. A checkout without the file (CI, a fresh clone) reports the gate as skipped.
$identityFile = Join-Path $PSScriptRoot 'private-identity.txt'
$identityTerms = @()
if (Test-Path -LiteralPath $identityFile) {
    $identityTerms = @(
        Get-Content -LiteralPath $identityFile |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and -not $_.StartsWith('#') }
    )
}
if ($identityTerms.Count -gt 0) {
    $identityArgs = @()
    foreach ($term in $identityTerms) { $identityArgs += @('-e', $term) }
    $identityMatches = @(& git -C $projectRoot grep -I -n -F -i @identityArgs -- . 2>$null)
    $identityExitCode = $LASTEXITCODE
    if ($identityExitCode -eq 0) {
        $identityLocations = @(
            $identityMatches | ForEach-Object {
                $parts = $_ -split ':', 3
                if ($parts.Count -ge 2) { "$($parts[0]):$($parts[1])" } else { $parts[0] }
            } | Sort-Object -Unique
        )
        # Deliberately reports locations only: echoing the matched line would print the identity
        # into a CI log, which is as public as the repository.
        throw "Operator identity found in tracked files at:`n$($identityLocations -join "`n")"
    }
    if ($identityExitCode -ne 1) {
        throw "Release hygiene identity scan failed with exit code $identityExitCode."
    }
    foreach ($term in $identityTerms) {
        $binarySensitivePatterns += ('(?i)' + [regex]::Escape($term))
    }
}

$binaryHits = [System.Collections.Generic.List[string]]::new()
foreach ($archivePath in $trackedFiles | Where-Object { $_ -match '(?i)\.(aar|jar|apk|aab)$' }) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $projectRoot $archivePath))
    try {
        foreach ($entry in $archive.Entries) {
            $entryStream = $entry.Open()
            try {
                $memory = [System.IO.MemoryStream]::new()
                $entryStream.CopyTo($memory)
                $content = [System.Text.Encoding]::GetEncoding(28591).GetString($memory.ToArray())
                foreach ($pattern in $binarySensitivePatterns) {
                    if ([regex]::IsMatch($content, $pattern)) {
                        $binaryHits.Add("$archivePath!$($entry.FullName)")
                        break
                    }
                }
            } finally {
                $entryStream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
}
if ($binaryHits.Count -gt 0) {
    throw "Sensitive material found inside tracked binary archives:`n$($binaryHits -join "`n")"
}

$bundledImages = @(
    $trackedFiles | Where-Object {
        $_ -match '^app/src/main/(assets|res)/' -and
        $_ -match '(?i)\.(png|jpg|jpeg|webp|avif)$'
    }
)

Write-Host "Release hygiene PASS: $($trackedFiles.Count) tracked files scanned; $($bundledImages.Count) bundled product images preserved."
# `git grep` returning one means "no matches", but PowerShell otherwise leaks that intentionally
# handled native exit code as the script's process exit code on GitHub Actions.
exit 0
