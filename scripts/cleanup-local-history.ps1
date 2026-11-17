#Requires -Version 5.1
<#
.SYNOPSIS
Removes historical WMKeyboard APKs and phone backups, keeping the latest of each.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\cleanup-local-history.ps1
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\cleanup-local-history.ps1 -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    # Workspace containing outputs/ and work/phone-backups/, not the Git checkout.
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

$ErrorActionPreference = 'Stop'

function Assert-PlainPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing a linked path: $Path"
    }
    return $item
}

$workspace = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath.TrimEnd('\', '/')
$null = Assert-PlainPath $workspace
$outputRoot = Join-Path $workspace 'outputs'
$backupRoot = Join-Path $workspace 'work\phone-backups'
foreach ($path in @($outputRoot, (Join-Path $workspace 'work'), $backupRoot)) {
    $null = Assert-PlainPath $path
}

$apks = @(Get-ChildItem -LiteralPath $outputRoot -File -Filter '*.apk' |
    Sort-Object -Property LastWriteTime, Name -Descending)
$backups = @(Get-ChildItem -LiteralPath $backupRoot -Directory |
    Where-Object { $_.Name -match '^\d{8}-\d{6}$' } | Sort-Object Name -Descending)
if ($apks.Count -eq 0 -or $backups.Count -eq 0) {
    throw 'Expected APKs and dated phone backups were not found. Nothing was removed.'
}

$keepApk = Assert-PlainPath $apks[0].FullName
$keepBackup = Assert-PlainPath $backups[0].FullName
$manifestPath = Join-Path $keepBackup.FullName 'manifest.json'
$null = Assert-PlainPath $manifestPath
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.package -ne 'com.wasimaster.wmkeyboard') {
    throw 'Unexpected package in the latest backup. Nothing was removed.'
}
foreach ($label in @('credential', 'device')) {
    $archive = Assert-PlainPath (Join-Path $keepBackup.FullName ($label + '.tar'))
    $entry = $manifest.archives.$label
    if ($null -eq $entry -or $archive.Length -ne $entry.bytes -or
        (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Latest backup failed verification: $label. Nothing was removed."
    }
}
$savedApks = @(Get-ChildItem -LiteralPath $keepBackup.FullName -File -Filter 'original-*.apk')
if ($manifest.apk_count -lt 1 -or $savedApks.Count -ne $manifest.apk_count) {
    throw 'The latest backup is missing its APK. Nothing was removed.'
}

$targets = [Collections.Generic.List[IO.FileSystemInfo]]::new()
foreach ($apk in ($apks | Select-Object -Skip 1)) {
    $targets.Add($apk)
    $signature = $apk.FullName + '.idsig'
    if (Test-Path -LiteralPath $signature) { $targets.Add((Get-Item -LiteralPath $signature)) }
}
foreach ($backup in ($backups | Select-Object -Skip 1)) { $targets.Add($backup) }

# Validate every absolute target before the first deletion. Keep all deletes
# within the two explicitly selected directories, without following junctions.
$plannedBytes = [long]0
foreach ($target in $targets) {
    $resolved = (Resolve-Path -LiteralPath $target.FullName).ProviderPath
    $parent = [IO.Path]::GetDirectoryName($resolved)
    if (($parent -ne $outputRoot -and $parent -ne $backupRoot) -or
        $resolved -eq $keepApk.FullName -or $resolved -eq $keepBackup.FullName) {
        throw "Refusing an unexpected deletion target: $resolved"
    }
    $null = Assert-PlainPath $resolved
    if ($target.PSIsContainer) {
        $pending = [Collections.Generic.Stack[string]]::new()
        $pending.Push($resolved)
        while ($pending.Count -gt 0) {
            foreach ($entry in (Get-ChildItem -LiteralPath $pending.Pop() -Force)) {
                $null = Assert-PlainPath $entry.FullName
                if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
                else { $plannedBytes += $entry.Length }
            }
        }
    } else { $plannedBytes += $target.Length }
}

Write-Host "Keeping APK: $($keepApk.Name)"
Write-Host "Keeping backup: $($keepBackup.Name)"
Write-Host ('Selected {0} old APKs and {1} old backups ({2:N2} GiB).' -f
    ($apks.Count - 1), ($backups.Count - 1), ($plannedBytes / 1GB))
foreach ($target in $targets) {
    if ($PSCmdlet.ShouldProcess($target.FullName, 'Delete historical local artifact')) {
        if ($target.PSIsContainer) { Remove-Item -LiteralPath $target.FullName -Recurse -Force }
        else { Remove-Item -LiteralPath $target.FullName -Force }
    }
}
if (-not $WhatIfPreference) {
    $remainingApks = @(Get-ChildItem -LiteralPath $outputRoot -File -Filter '*.apk')
    $remainingBackups = @(Get-ChildItem -LiteralPath $backupRoot -Directory |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' })
    if ($remainingApks.Count -ne 1 -or $remainingBackups.Count -ne 1) {
        throw 'Cleanup did not finish; check the remaining files.'
    }
    Write-Host ('Cleanup complete. Freed {0:N2} GiB.' -f ($plannedBytes / 1GB))
}
