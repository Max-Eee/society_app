# Chennai Coop Android App

Native Android app built with Kotlin and Jetpack Compose.

## USB development

Connect one Android phone by USB, enable **Developer options > USB debugging**, unlock the phone, and accept the computer's debugging authorization prompt.

The scripts automatically locate the Android SDK and JDK 17 already installed on this computer.

### Build, install, and run in DEV mode

```powershell
.\dev.cmd
```

This creates a debug build, reinstalls it while preserving app data, and opens the app. Run the same command after making code changes. Native Android does not have browser-style live reload, so changes must be rebuilt and installed.

To reinstall the last build without compiling it again:

```powershell
.\dev.cmd -SkipBuild
```

### Run in accelerated/high-refresh mode

```powershell
.\accelerated.cmd
```

Accelerated mode builds and installs the debug app, requests the phone's fastest display mode, requests 120 Hz from Android, disables battery saver, and enables Android's fixed-performance mode.

You can request another refresh rate:

```powershell
.\accelerated.cmd -RefreshRate 90
```

Android may still lower the real frame rate for static screens or thermal protection. Jetpack Compose only renders frames when UI content changes, so an idle screen is not expected to continuously render at 120 FPS. Accelerated mode consumes more power and can heat the phone.

Restore the phone's original refresh-rate and battery-saver settings when finished:

```powershell
.\restore-device.cmd
```

### Build an APK only

```powershell
.\build-apk.cmd
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

To build the release variant:

```powershell
.\build-apk.cmd -Configuration Release
```

The current release variant is not configured with a production signing key, so use the debug APK for direct USB installation.

## Requirements

- Windows PowerShell 5.1 or newer
- JDK 17 (Eclipse Temurin supported by the scripts)
- Android SDK with Platform Tools
- One authorized USB-debugging Android device for the run commands

