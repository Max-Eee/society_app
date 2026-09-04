param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug"
)

. (Join-Path $PSScriptRoot "AndroidTools.ps1")

$tooling = Get-AndroidTooling
Invoke-GradleTask -Tooling $tooling -Task "assemble$Configuration"

$outputDirectory = Join-Path $tooling.ProjectRoot "app\build\outputs\apk\$($Configuration.ToLowerInvariant())"
$apks = @(Get-ChildItem $outputDirectory -Filter "*.apk" -File -ErrorAction Stop)
Write-Host "APK build complete:" -ForegroundColor Green
$apks | ForEach-Object { Write-Host $_.FullName }
