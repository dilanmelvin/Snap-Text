# SnapText

SnapText is an Android app for copying visible text from almost any screen. Add the SnapText Quick Settings tile, tap it over another app, approve Android's screen capture prompt, and SnapText scans the current screen with on-device OCR. Detected words are highlighted on top of the screen. Tap a word to select it, then tap Copy.

The app is written in Kotlin with XML layouts, ViewBinding, Android MediaProjection, WindowManager overlays, and Google ML Kit Text Recognition.

## Download APK

After building the project, the debug APK is available here:

[Download app-debug.apk](app/build/outputs/apk/debug/app-debug.apk)

You can install this APK on an Android phone by transferring it to the phone and opening it, or by using Android Studio. Android may ask you to allow installing apps from that source.

Note: this is a debug APK. For sharing publicly, create and sign a release APK.

## What This App Does

- Captures the current visible screen after Android system approval.
- Runs OCR locally on the captured bitmap.
- Detects individual words instead of only whole lines.
- Shows a transparent overlay aligned to the captured screen.
- Lets the user tap a detected word to select it.
- Shows a Copy button only after a word is selected.
- Copies the selected word to the Android clipboard.
- Auto-dismisses the overlay if the user does nothing.

## Current User Flow

1. Open SnapText once.
2. Grant display-over-other-apps permission.
3. Add the SnapText tile to Quick Settings.
4. Open any app that has visible text.
5. Pull down Quick Settings and tap SnapText.
6. Approve the Android screen capture prompt.
7. Tap the word you want.
8. Tap Copy.

## Important Android Limitations

Android does not allow normal apps to capture the screen silently. The capture permission dialog is controlled by Android and cannot be removed by this app.

On Android 14 and newer, MediaProjection permissions are normally one-shot sessions. That means the capture prompt can appear each time the Quick Settings tile is used.

SnapText can only OCR text that is visible in the screenshot. It cannot read hidden content, scroll another app automatically, or extract text from protected screens that block screenshots.

## Tech Stack

- Language: Kotlin
- UI: Android XML layouts
- Binding: ViewBinding
- OCR: Google ML Kit Text Recognition
- Screen capture: Android MediaProjection
- Overlay: Android WindowManager application overlay
- Async work: Kotlin coroutines
- Minimum SDK: 26
- Target SDK: 34
- Compile SDK: 34
- Android Gradle Plugin: 8.7.3
- Gradle wrapper: 8.9
- Java: JDK 17 or newer

## Project Structure

```text
SnapText/
|-- app/
|   |-- build.gradle.kts
|   `-- src/main/
|       |-- AndroidManifest.xml
|       |-- kotlin/com/snaptext/app/
|       |   |-- MainActivity.kt
|       |   |-- capture/
|       |   |   |-- CapturePermissionActivity.kt
|       |   |   |-- CaptureResultReceiver.kt
|       |   |   `-- ScreenCaptureService.kt
|       |   |-- ocr/
|       |   |   `-- OcrEngine.kt
|       |   |-- overlay/
|       |   |   `-- OverlayManager.kt
|       |   |-- tile/
|       |   |   `-- SnapTileService.kt
|       |   `-- utils/
|       |       |-- ClipboardHelper.kt
|       |       `-- PermissionHelper.kt
|       `-- res/
|           |-- drawable/
|           |-- layout/
|           `-- values/
|-- build.gradle.kts
|-- gradle.properties
|-- settings.gradle.kts
|-- gradlew
`-- gradlew.bat
```

## Core Components

### MainActivity

The setup screen. It lets the user open Android overlay permission settings and shows whether the permission is currently granted.

### SnapTileService

The Quick Settings tile entry point. When the tile is tapped, it launches `CapturePermissionActivity` and collapses Quick Settings.

### CapturePermissionActivity

A transparent activity that requests screen capture permission using `MediaProjectionManager`. If overlay permission is missing, it sends the user back to setup.

### ScreenCaptureService

A foreground service that starts the MediaProjection session, captures one frame through `ImageReader`, converts it to a bitmap, runs OCR, then opens the selection overlay.

### OcrEngine

Runs ML Kit Text Recognition on the screenshot. It now extracts `Text.Element` results, which are word-level OCR elements, instead of copying whole detected lines.

### OverlayManager

Draws the full-screen overlay. Each detected word gets a touch target aligned to its OCR bounding box. Tapping a word selects it and shows a Copy button; copying dismisses the overlay.

## Build

Open the project in Android Studio and wait for Gradle Sync to finish.

From Windows PowerShell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat clean assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run In Android Studio

1. Open Android Studio.
2. Choose File > Open.
3. Select this SnapText project folder.
4. Wait for Gradle Sync.
5. Connect an Android phone or start an emulator.
6. Click Run.

A real phone is recommended because Quick Settings tile behavior and overlay permissions are easier to test on a device.

## Install The APK On A Phone

Option 1: Android Studio

1. Connect the phone with USB debugging enabled.
2. Select the phone in Android Studio.
3. Click Run.

Option 2: APK file

1. Build the debug APK.
2. Copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.
3. Open the APK on the phone.
4. Allow installation from that source if Android asks.
5. Open SnapText and complete setup.

Option 3: ADB

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Permissions

SnapText declares these permissions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

Permission purpose:

- `SYSTEM_ALERT_WINDOW`: lets SnapText draw selectable word boxes above other apps.
- `FOREGROUND_SERVICE`: lets screen capture work run in a foreground service.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: required for MediaProjection foreground services on modern Android versions.

## Testing Checklist

- Open SnapText and grant overlay permission.
- Add SnapText to Quick Settings.
- Open a screen with clear text.
- Tap the SnapText tile.
- Approve the capture prompt.
- Confirm detected words are highlighted close to the real word positions.
- Tap one word and confirm it selects without copying immediately.
- Tap Copy and confirm only that selected word is copied.
- Tap outside a selected word once and confirm selection clears.
- Tap outside again and confirm the overlay closes.
- Wait without action and confirm the overlay auto-dismisses.

## Troubleshooting

### Gradle says JAVA_HOME is missing

Set `JAVA_HOME` to Android Studio's bundled JDK:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

### Overlay does not appear

Open Android Settings and make sure SnapText has display-over-other-apps permission.

### Tile does not show

Pull down Quick Settings, tap the edit or pencil button, then manually add SnapText from the available tiles.

### Screen capture prompt appears every time

This is expected on newer Android versions. Android controls this for privacy.

### OCR selection is slightly off

OCR bounding boxes come from ML Kit and depend on screenshot quality, text size, contrast, font, and background. Static high-contrast text works best.

### Some words are missing

Very tiny text, stylized logos, low-contrast UI text, and protected screenshots may not be detected reliably.

## Release Build

Generate an unsigned release APK:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

Release APK output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign the release APK before distributing it publicly.

## Privacy

SnapText performs OCR locally on the device. It has no backend server, no account system, no database, and no analytics. Captured screenshots are processed in memory and are not uploaded by this app.

## Roadmap Ideas

- Drag selection across multiple words.
- Copy full line or copy all detected text mode.
- Better language selection for non-English OCR.
- Persistent quick action panel for copy, search, and share.
- Automated UI and instrumentation tests.
- Signed release workflow with a public GitHub Releases download link.
