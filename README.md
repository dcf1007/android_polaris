# Polaris Alignment Android wrapper

This project wraps `accurate_polaris_alignment_sanitized.html` as an offline Android WebView app.

## Important camera note

Camera2 is used to list cameras exposed through Android's `CameraManager`.

USB UVC cameras are not guaranteed to appear in Camera2 on every Android device. They appear in Camera2 only if Android exposes them through the camera provider / HAL. To avoid missing attached UVC hardware, the app also enumerates USB host devices through `UsbManager` and flags devices or interfaces with USB video class `14` as UVC candidates.

Therefore the APK diagnostic panel shows two lists:

1. `camera2Devices` — cameras Android exposes through Camera2.
2. `usbDevices` — attached USB devices, with `looksLikeUvcVideoDevice` when a UVC/video-class interface is detected.

## Build

Open this folder in Android Studio and run:

```bash
./gradlew assembleDebug
```

or use Android Studio's **Build > Build APK(s)** command.

A debug APK should be produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This environment did not contain the Android SDK, Gradle, `aapt2`, `d8`, or `apksigner`, so the APK could not be compiled here. The source project is complete and importable in Android Studio.

## Offline behavior

The alignment page is loaded from:

```text
app/src/main/assets/index.html
```

No internet permission is declared and the app does not fetch external resources.

## Native bridge

The WebView exposes:

```js
AndroidCameraBridge.getCameraAndUsbReportJson()
```

The injected diagnostics panel calls this method and prints a JSON report.
