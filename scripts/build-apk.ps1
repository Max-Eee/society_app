param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug"
)

. (Join-Path $PSScriptRoot "AndroidTools.ps1")

$tooling = Get-AndroidTooling
Invoke-GradleTask -Tooling $tooling -Task "assemble$Configuration"

$configurationDirectory = $Configuration.ToLowerInvariant()
$flavors = @("issue", "scan", "all")
$apks = @(
    foreach ($flavor in $flavors) {
        $outputDirectory = Join-Path $tooling.ProjectRoot "app\build\outputs\apk\$flavor\$configurationDirectory"
        $apk = Get-ChildItem $outputDirectory -Filter "*.apk" -File -ErrorAction Stop |
            Select-Object -First 1
        if (-not $apk) {
            throw "The $flavor $Configuration APK was not created in $outputDirectory"
        }
        $apk
    }
)

Write-Host "Three APK variants built successfully:" -ForegroundColor Green
$apks | ForEach-Object { Write-Host $_.FullName }
