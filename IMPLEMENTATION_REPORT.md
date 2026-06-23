# Implementation report

## Scope implemented

Refactored the accepted Polaris alignment webpage concept into a native Android project using Java and Camera2.

Implemented:

- Native Android single-activity app.
- Camera2 camera enumeration, camera permission handling, and live preview.
- Camera2 external-camera detection for USB/UVC cameras exposed by Android as external cameras.
- USB Host UVC device detection and permission request workflow.
- Native reticle overlay drawn over the camera preview.
- Offline Polaris alignment calculation ported from the sanitized web implementation.
- Fixed Bennett refraction as the default.
- ΔT / TT separation retained:
  - TT for apparent-place terms;
  - UTC as the offline UT1 proxy for sidereal/Earth-rotation terms.
- GitHub Actions workflow to build and upload a debug APK.

## Deliberately not implemented

- No browser-view wrapper.
- No third-party UVC native driver.
- No CameraX.
- No SOFA/ERFA kernel.
- No IERS data download or embedded EOP table.
- No SA-console or compatibility placement mode.

## UVC compatibility design

Android USB cameras are not uniformly exposed to apps. This project supports the reliable native path first: Camera2. If an OTG/UVC camera appears as `LENS_FACING_EXTERNAL`, the app can use it directly through Camera2 preview.

For raw USB Video Class devices, the app detects the device and requests USB permission. Raw streaming is intentionally isolated from the rest of the app because it requires a UVC streaming implementation, usually native/libuvc-based. This separation prevents the astronomy and Camera2 code from being coupled to a specific third-party UVC library.

## Code organization

- `MainActivity`: UI and lifecycle orchestration only.
- `Camera2PreviewController`: Camera2-specific lifecycle and preview code.
- `UsbUvcDeviceMonitor`: USB-host device enumeration and permission handling.
- `PolarisAlignmentCalculator`: all alignment math.
- `ReticleOverlayView`: all drawing in a verified SVG coordinate system.
- `model`: immutable calculation input and output types.
- `util`: presentation formatting only.

## Validation performed locally

- Checked for duplicate helper/class declarations in generated source.
- Checked for duplicate XML resource files in the generated project layout.
- Checked for placeholder markers: none used for required behavior.
- Checked that Camera2 and USB Host classes are separated.

A full Gradle build was not run in this sandbox because the environment does not have internet access to download the Android Gradle Plugin and SDK packages. The GitHub workflow is provided to run the build in GitHub Actions.
