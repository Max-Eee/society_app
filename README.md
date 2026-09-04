# Chennai Coop Android App

Native Android app built with Kotlin and Jetpack Compose.

## USB development

Connect one Android phone by USB, enable **Developer options > USB debugging**, unlock the phone, and accept the computer's debugging authorization prompt.

The scripts automatically locate the Android SDK and JDK 17 already installed on this computer.

### Build, install, and run in DEV mode

```bash
./dev.cmd
```

This creates a debug build, reinstalls it while preserving app data, and opens the app. Run the same command after making code changes. Native Android does not have browser-style live reload, so changes must be rebuilt and installed.

To reinstall the last build without compiling it again:

```bash
./dev.cmd -SkipBuild
```

### Run in accelerated/high-refresh mode

```bash
./accelerated.cmd
```

Accelerated mode builds and installs the debug app, requests the phone's fastest display mode, requests 120 Hz from Android, disables battery saver, and enables Android's fixed-performance mode.

You can request another refresh rate:

```bash
./accelerated.cmd -RefreshRate 90
```

Android may still lower the real frame rate for static screens or thermal protection. Jetpack Compose only renders frames when UI content changes, so an idle screen is not expected to continuously render at 120 FPS. Accelerated mode consumes more power and can heat the phone.

Restore the phone's original refresh-rate and battery-saver settings when finished:

```bash
./restore-device.cmd
```

### Build an APK only

```bash
./build-apk.cmd
```

This one command builds all three debug variants:

- `app/build/outputs/apk/issue/debug/app-issue-debug.apk` — Issue, Report, and Printer
- `app/build/outputs/apk/scan/debug/app-scan-debug.apk` — Scan, Report, and Printer
- `app/build/outputs/apk/all/debug/app-all-debug.apk` — all four tabs

To build the release variant:

```bash
./build-apk.cmd -Configuration Release
```

The same three flavors are generated under their respective `release` directories. Release APKs are not configured with a production signing key, so use the debug APKs for direct USB installation.

## Generate group sweet-list PDFs

Group PDFs are generated from the current Supabase `ccocs` data. Every PDF contains the society and meeting details, a secured group QR, members ordered by numeric member number, signature spaces, signed-member count, and an attestation area.

Generate one PDF for every group:

```bash
./generate-group-pdfs.cmd
```

Generate a single group while checking the layout:

```bash
./generate-group-pdfs.cmd --group-id EDHS013
```

Override meeting details when required:

```bash
./generate-group-pdfs.cmd --meeting-date "12/01/2026 at 11:00 AM" --venue "Conference Hall, Admin Building"
```

Files are written to `output/pdf/group-forms`. The QR stores an opaque per-group UUID plus an HMAC signature; it does not expose the station-derived group ID.

## Requirements

- Windows PowerShell 5.1 or newer
- JDK 17 (Eclipse Temurin supported by the scripts)
- Android SDK with Platform Tools
- One authorized USB-debugging Android device for the run commands

