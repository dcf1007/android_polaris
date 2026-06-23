# Android camera / UVC implementation report

## Base page

The wrapped alignment page is:

```text
app/src/main/assets/index.html
```

It is copied from the latest sanitized offline HTML version.

## Native Android wrapper

Native source file:

```text
app/src/main/java/com/dcf1007/polarisalignment/MainActivity.java
```

The Android wrapper:

1. Creates a `WebView` programmatically.
2. Enables JavaScript and DOM storage for the offline alignment page.
3. Loads `file:///android_asset/index.html`.
4. Exposes `AndroidCameraBridge` to JavaScript.
5. Injects an Android-only diagnostic fieldset after the page loads.

## Camera2 enumeration

The bridge calls:

```java
CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
cameraManager.getCameraIdList();
cameraManager.getCameraCharacteristics(cameraId);
```

For every Camera2 device it reports:

- camera ID;
- lens facing: front, back, external, or unknown;
- hardware level;
- available capabilities;
- whether Camera2 reports it as an external camera.

## USB UVC enumeration

Because Camera2 cannot guarantee that every attached USB UVC camera appears as a Camera2 device, the bridge also calls:

```java
UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
usbManager.getDeviceList();
```

For each USB device it reports:

- device name;
- vendor ID;
- product ID;
- device class/subclass/protocol;
- USB permission state;
- all interfaces;
- whether any device/interface class is USB video class 14.

A detected USB video-class interface sets:

```json
"looksLikeUvcVideoDevice": true
```

## Manifest additions

The manifest declares:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

The USB host feature is not required so the app remains installable on devices without USB host support.

## Limitations

This project lists cameras and USB UVC candidates. It does not yet implement live UVC video streaming or camera preview overlay.

To actually open and display UVC cameras that are not exposed through Camera2, a UVC/libuvc/USB camera stack must be added. Camera2 alone cannot force arbitrary UVC devices into the Android Camera2 camera list.
