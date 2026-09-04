. (Join-Path $PSScriptRoot "AndroidTools.ps1")

$tooling = Get-AndroidTooling
$serial = Get-AuthorizedDevice -Adb $tooling.Adb
$stateFile = Join-Path $env:TEMP "society-app-device-$serial.json"

if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Host "No saved accelerated-mode settings were found for device $serial."
    exit 0
}

$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json

foreach ($setting in @(
    @{ Name = "peak_refresh_rate"; Value = $state.peakRefreshRate },
    @{ Name = "min_refresh_rate"; Value = $state.minRefreshRate }
)) {
    if (-not $setting.Value -or $setting.Value -eq "null") {
        & $tooling.Adb -s $serial shell settings delete system $setting.Name | Out-Null
    }
    else {
        & $tooling.Adb -s $serial shell settings put system $setting.Name $setting.Value | Out-Null
    }
}

$lowPower = if ($state.lowPower -eq "1") { "1" } else { "0" }
& $tooling.Adb -s $serial shell cmd power set-mode $lowPower | Out-Null
& $tooling.Adb -s $serial shell cmd power set-fixed-performance-mode-enabled false | Out-Null
Remove-Item -LiteralPath $stateFile

Write-Host "Device refresh-rate and power settings were restored." -ForegroundColor Green
