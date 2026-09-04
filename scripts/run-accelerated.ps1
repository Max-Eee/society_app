param(
    [double]$RefreshRate = 120.0,
    [switch]$SkipBuild
)

. (Join-Path $PSScriptRoot "AndroidTools.ps1")

$tooling = Get-AndroidTooling
$serial = Get-AuthorizedDevice -Adb $tooling.Adb
$stateFile = Join-Path $env:TEMP "society-app-device-$serial.json"

if (-not $SkipBuild) {
    Invoke-GradleTask -Tooling $tooling -Task "assembleAllDebug"
}
Install-DebugApk -Tooling $tooling -Serial $serial

if (-not (Test-Path -LiteralPath $stateFile)) {
    $state = [PSCustomObject]@{
        peakRefreshRate = ((& $tooling.Adb -s $serial shell settings get system peak_refresh_rate) | Out-String).Trim()
        minRefreshRate = ((& $tooling.Adb -s $serial shell settings get system min_refresh_rate) | Out-String).Trim()
        lowPower = ((& $tooling.Adb -s $serial shell settings get global low_power) | Out-String).Trim()
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
}

& $tooling.Adb -s $serial shell settings put system peak_refresh_rate $RefreshRate | Out-Null
& $tooling.Adb -s $serial shell settings put system min_refresh_rate $RefreshRate | Out-Null
& $tooling.Adb -s $serial shell cmd power set-mode 0 | Out-Null
& $tooling.Adb -s $serial shell cmd power set-fixed-performance-mode-enabled true | Out-Null

Start-SocietyApp -Tooling $tooling -Serial $serial -Accelerated

Write-Host "ACCELERATED app is running at a requested $RefreshRate Hz on device $serial." -ForegroundColor Green
Write-Warning "This mode uses more battery and may heat the phone. Run .\restore-device.cmd when finished."
