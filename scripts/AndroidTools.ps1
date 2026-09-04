Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-AndroidTooling {
    $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    $sdk = $sdkCandidates | Select-Object -First 1
    if (-not $sdk) {
        throw "Android SDK not found. Install Android SDK Platform Tools or set ANDROID_HOME."
    }

    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb)) {
        throw "ADB was not found at $adb. Install Android SDK Platform Tools."
    }

    $jdkCandidates = @()
    if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
    if (Test-Path -LiteralPath "C:\Program Files\Eclipse Adoptium") {
        $jdkCandidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" |
            Sort-Object Name -Descending |
            Select-Object -ExpandProperty FullName
    }

    $jdk = $jdkCandidates |
        Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") } |
        Where-Object {
            $releaseFile = Join-Path $_ "release"
            (Test-Path -LiteralPath $releaseFile) -and
                ((Get-Content -LiteralPath $releaseFile -Raw) -match 'JAVA_VERSION="17\.')
        } |
        Select-Object -First 1

    if (-not $jdk) {
        throw "JDK 17 not found. Install Eclipse Temurin JDK 17."
    }

    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:JAVA_HOME = $jdk
    $env:PATH = "$(Join-Path $jdk 'bin');$(Join-Path $sdk 'platform-tools');$env:PATH"

    [PSCustomObject]@{
        ProjectRoot = $projectRoot
        Sdk = $sdk
        Adb = $adb
        JavaHome = $jdk
    }
}

function Get-AuthorizedDevice {
    param([Parameter(Mandatory)][string]$Adb)

    & $Adb start-server | Out-Null
    $deviceLines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
    $authorized = @($deviceLines | Where-Object { $_ -match '\sdevice$' })

    if ($authorized.Count -eq 0) {
        if ($deviceLines -match '\sunauthorized$') {
            throw "The USB device is unauthorized. Unlock it and accept the USB debugging prompt."
        }
        throw "No authorized Android device found. Connect the phone and enable USB debugging."
    }
    if ($authorized.Count -gt 1) {
        throw "More than one Android device is connected. Disconnect the devices you do not want to use."
    }

    ($authorized[0] -split '\s+')[0]
}

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory)]$Tooling,
        [Parameter(Mandatory)][string]$Task
    )

    Push-Location $Tooling.ProjectRoot
    try {
        & (Join-Path $Tooling.ProjectRoot "gradlew.bat") $Task
        if ($LASTEXITCODE -ne 0) { throw "Gradle task '$Task' failed." }
    }
    finally {
        Pop-Location
    }
}

function Install-DebugApk {
    param(
        [Parameter(Mandatory)]$Tooling,
        [Parameter(Mandatory)][string]$Serial
    )

    $apk = Join-Path $Tooling.ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK was not created at $apk" }
    # Push install is more reliable than streamed install on some USB connections.
    & $Tooling.Adb -s $Serial install --no-streaming -r $apk
    if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
}

function Start-SocietyApp {
    param(
        [Parameter(Mandatory)]$Tooling,
        [Parameter(Mandatory)][string]$Serial,
        [switch]$Accelerated
    )

    $package = "com.example.Chennai_Coop"
    & $Tooling.Adb -s $Serial shell cmd statusbar collapse | Out-Null
    & $Tooling.Adb -s $Serial shell am force-stop $package | Out-Null

    $arguments = @("-s", $Serial, "shell", "am", "start", "-W", "-n", "$package/.MainActivity")
    if ($Accelerated) { $arguments += @("--ez", "accelerated_mode", "true") }
    & $Tooling.Adb @arguments
    if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
}
