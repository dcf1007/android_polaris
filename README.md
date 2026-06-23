# Android Polaris

Native Android USB OTG / UVC refactor of the Polaris polar-alignment page for Star Adventurer-style polar-scope work.

## What the app does

- Draws a native reticle overlay over a live USB UVC camera preview.
- Uses `AndroidUSBCamera/libausbc` for USB camera preview.
- Computes Polaris as an apparent/topocentric polar-scope target using the accepted offline model from the web implementation.
- Keeps the verified SVG geometry as the coordinate reference:
  - viewBox: `1501.99 × 1498.19`
  - NCP/epicentre/crosshair center: `(746.01, 746.43)`
  - 2012/2020/2028 year-ring radii: `358.5`, `342.5`, `326.5` SVG px
- Builds a debug APK through GitHub Actions.

## Camera support

This version is **USB OTG / UVC only**. Built-in phone cameras are not listed or opened.

The preview path is:

```text
USB OTG UVC camera
→ Android USB Host permission
→ AUSBC / libuvc preview backend
→ native TextureView preview
→ native reticle overlay
```

The UVC backend is based on `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3`.

## Build locally

Install Android SDK platform 35, Build Tools 34.0.0, JDK 17, and Gradle 8.9. Then run:

```bash
gradle --no-daemon assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build with GitHub Actions

The workflow file is:

```text
.github/workflows/android-debug-apk.yml
```

It installs the required Android SDK packages and uploads the debug APK as an artifact named:

```text
android-polaris-debug-apk
```

## Project structure

```text
app/src/main/java/com/dcf1007/androidpolaris/
  MainActivity.java                       UI, permissions, live clock, UVC wiring
  astro/AstroMath.java                    Unit conversion, Julian date, ΔT helpers
  astro/PolarisAlignmentCalculator.java   Offline apparent/topocentric Polaris model
  camera/UvcPreviewController.java        USB Host permission and AUSBC/libuvc preview lifecycle
  model/*.java                            Immutable input/result/refraction models
  util/UiFormatting.java                  UI formatting only
  view/ReticleOverlayView.java            Native reticle and Polaris marker drawing
```

## Astronomical model summary

The calculation remains fully offline and intentionally follows the sanitized web implementation:

1. Start from Polaris J2000/ICRS coordinates.
2. Apply proper motion.
3. Use TT for proper motion epoch, precession, nutation, and annual aberration.
4. Use UTC as a UT1 proxy for Earth rotation and sidereal time.
5. Apply compact nutation and annual aberration.
6. Compute local apparent sidereal time and Polaris hour angle.
7. Apply Bennett atmospheric refraction. The default is fixed Bennett.
8. Project the observed pole separation using tangent-plane radial scaling.
9. Convert the marker to the verified SVG coordinate system.

No IERS EOP table, full IAU 2000A/2000B nutation, SOFA/ERFA kernel, or compatibility/SA-console mode is included.
