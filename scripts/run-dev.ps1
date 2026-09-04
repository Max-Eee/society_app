param([switch]$SkipBuild)

. (Join-Path $PSScriptRoot "AndroidTools.ps1")

$tooling = Get-AndroidTooling
$serial = Get-AuthorizedDevice -Adb $tooling.Adb

if (-not $SkipBuild) {
    Invoke-GradleTask -Tooling $tooling -Task "assembleAllDebug"
}
Install-DebugApk -Tooling $tooling -Serial $serial
Start-SocietyApp -Tooling $tooling -Serial $serial

Write-Host "DEV app is running on device $serial." -ForegroundColor Green
