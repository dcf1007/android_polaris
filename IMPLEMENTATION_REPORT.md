# Implementation report

## Scope implemented

Refactored the accepted Polaris alignment webpage concept into a native Android project using Java and a USB OTG / UVC camera backend.

Implemented:

- Native Android single-activity app.
- USB OTG / UVC preview through `AndroidUSBCamera/libausbc` 3.3.3.
- Android USB Host permission workflow for the connected UVC device.
- Native reticle overlay drawn over the UVC preview.
- Offline Polaris alignment calculation ported from the sanitized web implementation.
- Fixed Bennett refraction as the default.
- ΔT / TT separation retained:
  - TT for apparent-place terms;
  - UTC as the offline UT1 proxy for sidereal/Earth-rotation terms.
- GitHub Actions workflow to build and upload a debug APK.

## Build stack

The GitHub Actions workflow uses a conservative Android build stack for CI reliability:

- Android Gradle Plugin 8.7.3.
- Gradle 8.9.
- Android SDK platform 35.
- Android SDK Build Tools 34.0.0.
- JDK 17.

The source code does not require API 36/37-specific behavior, so compile/target SDK 35 is sufficient for this testing APK and avoids depending on the newest SDK packages being available in the CI runner.

## UVC-only design

The active preview path is:

```text
USB OTG UVC camera
→ Android USB Host permission
→ AUSBC / libuvc preview backend
→ native TextureView preview
→ native reticle overlay
```

Camera2 enumeration and built-in phone camera preview are not part of the active app flow. The app is aimed at the USB polar-scope camera, because that is the hardware that matters for alignment testing.

## Code organization

- `MainActivity`: UI, permissions, live clock, and UVC-controller wiring.
- `UvcPreviewController`: USB Host permission, AUSBC device callbacks, and UVC preview lifecycle.
- `PolarisAlignmentCalculator`: all alignment math.
- `ReticleOverlayView`: all drawing in a verified SVG coordinate system.
- `model`: immutable calculation input and output types.
- `util`: presentation formatting only.

## Validation performed locally

- Checked that the active Activity imports and uses `UvcPreviewController`, not a Camera2 preview controller.
- Kept astronomy code and camera code separated.
- Kept the verified reticle center and ring geometry unchanged.

A full Gradle build was not run in this sandbox because the environment does not have internet access to download the Android Gradle Plugin, SDK packages, and JitPack dependencies. The GitHub workflow is provided to run the build in GitHub Actions.
