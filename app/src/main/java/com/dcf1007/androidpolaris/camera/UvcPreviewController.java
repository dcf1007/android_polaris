package com.dcf1007.androidpolaris.camera;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend-only direct UVC controller.
 *
 * <p>This class owns USB monitoring, UVC permission, direct {@link UVCCamera} opening,
 * capability queries, preview stream selection and low-level camera controls. It deliberately
 * does not create visible Android controls. MainActivity owns the dropdowns/sliders and calls
 * this backend after binding queried capabilities into the main camera panel.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int MANUAL_EXPOSURE_MODE = 1;
    private static final int AUTO_EXPOSURE_MODE = 2;
    private static final int STREAM_TYPE_YUYV = 4;
    private static final int STREAM_TYPE_MJPEG = 6;
    private static final long PREVIEW_SURFACE_RETRY_DELAY_MS = 250L;
    private static final float DEFAULT_PREVIEW_ASPECT_RATIO = 4.0f / 3.0f;

    public enum PreviewFitMode { COVER, CONTAIN, STRETCH }

    public interface Listener {
        void onUvcStatusChanged(String statusText);
        default void onUvcCapabilitiesChanged(UvcCapabilities capabilities) { }
    }

    public static final class StreamMode {
        public final int frameFormat;
        public final int width;
        public final int height;
        public final int fps;

        StreamMode(int frameFormat, int width, int height, int fps) {
            this.frameFormat = frameFormat;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        public String formatLabel() {
            return frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG / compressed" : "YUYV / uncompressed";
        }

        public String resolutionLabel() {
            return width + "×" + height;
        }

        public String fpsLabel() {
            return fps + " fps";
        }

        public String fullLabel() {
            return resolutionLabel() + " " + formatLabel() + " @ " + fpsLabel();
        }
    }

    public static final class UvcCapabilities {
        public final boolean cameraOpen;
        public final List<StreamMode> streamModes;
        public final StreamMode activeStreamMode;
        public final boolean brightnessSupported;
        public final boolean contrastSupported;
        public final boolean gainSupported;
        public final boolean exposureSupported;
        public final boolean autoExposureSupported;
        public final int exposurePercent;
        public final boolean autoExposureEnabled;
        public final String exposureRangeText;
        public final String statusText;

        UvcCapabilities(
                boolean cameraOpen,
                List<StreamMode> streamModes,
                StreamMode activeStreamMode,
                boolean brightnessSupported,
                boolean contrastSupported,
                boolean gainSupported,
                boolean exposureSupported,
                boolean autoExposureSupported,
                int exposurePercent,
                boolean autoExposureEnabled,
                String exposureRangeText,
                String statusText) {
            this.cameraOpen = cameraOpen;
            this.streamModes = Collections.unmodifiableList(new ArrayList<>(streamModes));
            this.activeStreamMode = activeStreamMode;
            this.brightnessSupported = brightnessSupported;
            this.contrastSupported = contrastSupported;
            this.gainSupported = gainSupported;
            this.exposureSupported = exposureSupported;
            this.autoExposureSupported = autoExposureSupported;
            this.exposurePercent = exposurePercent;
            this.autoExposureEnabled = autoExposureEnabled;
            this.exposureRangeText = exposureRangeText;
            this.statusText = statusText;
        }
    }

    private final Context context;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout previewContainer;
    private final TextureView previewTextureView;
    private final Listener listener;
    private final USBMonitor usbMonitor;
    private final Map<Integer, UsbDevice> detectedUvcDevicesById = new LinkedHashMap<>();

    private UsbDevice pendingOpenDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private UVCCamera activeCamera;
    private PreviewFitMode previewFitMode = PreviewFitMode.COVER;
    private boolean usbMonitorRegistered;
    private boolean openRetryScheduled;

    private String supportedSizeJson;
    private List<StreamMode> availableStreamModes = new ArrayList<>();
    private StreamMode activeStreamMode;
    private StreamMode requestedStreamMode;

    private int brightnessPercent = 50;
    private int contrastPercent = 50;
    private int gainPercent = 0;
    private int exposurePercent = 50;
    private boolean autoExposureEnabled = true;
    private boolean blackWhiteEnabled;

    private boolean brightnessSupported;
    private boolean contrastSupported;
    private boolean gainSupported;
    private boolean exposureSupported;
    private boolean autoExposureSupported;

    private Field exposureNativePointerField;
    private Field exposureMinimumField;
    private Field exposureMaximumField;
    private Field exposureDefaultField;
    private Field exposureModeDefaultField;
    private Method updateExposureLimitMethod;
    private Method getExposureMethod;
    private Method setExposureMethod;
    private Method updateExposureModeLimitMethod;
    private Method getExposureModeMethod;
    private Method setExposureModeMethod;
    private boolean nativeExposureAccessReady;

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;
        this.previewTextureView = new TextureView(context);
        this.usbMonitor = new USBMonitor(context, createUsbConnectionListener());
        configurePreviewTextureView();
        applyPreviewFitModeLater();
        publishCapabilities("UVC backend ready. USB has not been scanned yet.");
    }

    public boolean register() {
        if (usbMonitorRegistered) {
            refreshDetectedUvcDeviceCache();
            publishCapabilities("USB monitor already registered. Detected UVC devices: " + detectedUvcDevicesById.size() + ".");
            return true;
        }
        try {
            usbMonitor.register();
            usbMonitorRegistered = true;
            refreshDetectedUvcDeviceCache();
            publishCapabilities("USB monitor registered. Detected UVC devices: " + detectedUvcDevicesById.size() + ".");
            return true;
        } catch (Throwable throwable) {
            usbMonitorRegistered = false;
            publishCapabilities("USB monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    public void unregister() {
        closeActiveCamera();
        if (!usbMonitorRegistered) return;
        try {
            usbMonitor.unregister();
        } catch (Throwable throwable) {
            publishCapabilities("USB monitor unregister warning: " + describeThrowable(throwable));
        } finally {
            usbMonitorRegistered = false;
        }
    }

    public void destroy() {
        closeActiveCamera();
        detectedUvcDevicesById.clear();
        try {
            usbMonitor.destroy();
        } catch (Throwable throwable) {
            publishCapabilities("USB monitor destroy warning: " + describeThrowable(throwable));
        }
        previewContainer.removeAllViews();
    }

    public boolean hasDetectedUvcDevice() {
        refreshDetectedUvcDeviceCache();
        return !detectedUvcDevicesById.isEmpty();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) return;
        refreshDetectedUvcDeviceCache();
        UsbDevice firstDevice = getFirstDetectedUvcDevice();
        if (firstDevice == null) {
            publishCapabilities("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }
        pendingOpenDevice = firstDevice;
        publishCapabilities("UVC detected: " + describeDeviceBrief(firstDevice) + ". Requesting USB permission…");
        try {
            if (usbMonitor.requestPermission(firstDevice)) {
                publishCapabilities("USB permission request could not start. Reconnect the camera and try again.");
            }
        } catch (Throwable throwable) {
            publishCapabilities("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public void closeActiveCamera() {
        try {
            if (activeCamera != null) activeCamera.destroy();
        } catch (Throwable throwable) {
            notifyStatus("Direct libuvc close warning: " + describeThrowable(throwable));
        } finally {
            activeCamera = null;
            activeControlBlock = null;
            activeStreamMode = null;
            supportedSizeJson = null;
            availableStreamModes = new ArrayList<>();
            brightnessSupported = false;
            contrastSupported = false;
            gainSupported = false;
            exposureSupported = false;
            autoExposureSupported = false;
            clearNativeExposureAccess();
            openRetryScheduled = false;
            publishCapabilities("UVC camera closed.");
        }
    }

    public String describeConnectedUvcDevices() {
        refreshDetectedUvcDeviceCache();
        if (detectedUvcDevicesById.isEmpty()) return "No raw USB UVC devices detected.";
        StringBuilder builder = new StringBuilder();
        builder.append(detectedUvcDevicesById.size()).append(" raw UVC device(s) detected:\n");
        for (UsbDevice device : detectedUvcDevicesById.values()) builder.append(describeDeviceLong(device)).append('\n');
        if (activeStreamMode != null) {
            builder.append("Active stream: ").append(activeStreamMode.fullLabel()).append('\n');
            builder.append("Controls: brightness=").append(brightnessSupported)
                    .append(", contrast=").append(contrastSupported)
                    .append(", gain=").append(gainSupported)
                    .append(", exposure=").append(exposureSupported)
                    .append(", auto exposure=").append(autoExposureSupported).append('\n');
            builder.append(describeStreamModes());
        }
        return builder.toString().trim();
    }

    public UvcCapabilities getCurrentCapabilities() {
        return buildCapabilities(activeCamera == null ? "No UVC camera open." : "UVC capabilities queried.");
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    public void selectStreamMode(StreamMode streamMode) {
        if (streamMode == null) return;
        requestedStreamMode = streamMode;
        if (activeCamera != null && pendingOpenDevice != null && activeControlBlock != null) {
            openDirectUvcCamera(pendingOpenDevice, activeControlBlock, true);
        }
    }

    public void setCameraControls(
            int brightnessPercent,
            int contrastPercent,
            int gainPercent,
            int exposurePercent,
            boolean autoExposureEnabled,
            boolean blackWhiteEnabled) {
        this.brightnessPercent = clamp(brightnessPercent, 0, 100);
        this.contrastPercent = clamp(contrastPercent, 0, 100);
        this.gainPercent = clamp(gainPercent, 0, 100);
        this.exposurePercent = clamp(exposurePercent, 0, 100);
        this.autoExposureEnabled = autoExposureEnabled;
        this.blackWhiteEnabled = blackWhiteEnabled;
        applyDisplaySideImageFilter();
        applyCameraControls("main panel control change");
    }

    private void configurePreviewTextureView() {
        previewContainer.removeAllViews();
        previewContainer.addView(previewTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        previewContainer.setVisibility(View.VISIBLE);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) applyPreviewFitMode();
            }
        });
        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                applyPreviewFitMode();
                if (pendingOpenDevice != null && activeControlBlock != null) openCameraWhenSurfaceIsReady(pendingOpenDevice, activeControlBlock);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { applyPreviewFitMode(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) { closeActiveCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });
    }

    private USBMonitor.OnDeviceConnectListener createUsbConnectionListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) {
                if (isUvcVideoDevice(device)) {
                    rememberDetectedDevice(device);
                    publishCapabilities("UVC attached: " + describeDeviceBrief(device) + ".");
                }
            }
            @Override public void onDettach(UsbDevice device) {
                if (device != null) detectedUvcDevicesById.remove(device.getDeviceId());
                closeActiveCamera();
                publishCapabilities("UVC detached" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock, boolean createNew) {
                if (!isUvcVideoDevice(device) || controlBlock == null) return;
                rememberDetectedDevice(device);
                pendingOpenDevice = device;
                activeControlBlock = controlBlock;
                openCameraWhenSurfaceIsReady(device, controlBlock);
            }
            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                closeActiveCamera();
                publishCapabilities("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onCancel(UsbDevice device) {
                pendingOpenDevice = null;
                publishCapabilities("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
            }
        };
    }

    private void openCameraWhenSurfaceIsReady(final UsbDevice device, final USBMonitor.UsbControlBlock controlBlock) {
        mainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (!previewTextureView.isAvailable() || previewTextureView.getSurfaceTexture() == null) {
                    scheduleOpenRetry(device, controlBlock);
                    return;
                }
                openDirectUvcCamera(device, controlBlock, false);
            }
        });
    }

    private void openDirectUvcCamera(UsbDevice device, USBMonitor.UsbControlBlock controlBlock, boolean keepRequestedMode) {
        try {
            if (activeCamera != null) activeCamera.destroy();
        } catch (Throwable ignored) { }
        activeCamera = null;
        clearNativeExposureAccess();
        try {
            UVCCamera camera = new UVCCamera();
            camera.open(controlBlock);
            supportedSizeJson = camera.getSupportedSize();
            availableStreamModes = queryStreamModes(supportedSizeJson);
            StreamMode selectedMode = keepRequestedMode && requestedStreamMode != null
                    ? findEquivalentModeOrDefault(requestedStreamMode)
                    : chooseDefaultStreamMode();
            if (selectedMode == null) throw new IllegalStateException("No supported UVC preview mode reported by camera");
            camera.setPreviewSize(selectedMode.width, selectedMode.height, selectedMode.fps, selectedMode.fps,
                    selectedMode.frameFormat, UVCCamera.DEFAULT_BANDWIDTH);
            camera.setPreviewTexture(previewTextureView.getSurfaceTexture());
            camera.startPreview();
            camera.updateCameraParams();
            activeCamera = camera;
            activeControlBlock = controlBlock;
            pendingOpenDevice = device;
            activeStreamMode = selectedMode;
            requestedStreamMode = selectedMode;
            prepareNativeExposureAccess();
            queryControlSupportAndCurrentValues();
            applyCameraControls("camera opened");
            applyPreviewFitMode();
            publishCapabilities("UVC capabilities queried. Preview opened at " + selectedMode.fullLabel() + ".");
        } catch (Throwable throwable) {
            activeCamera = null;
            clearNativeExposureAccess();
            publishCapabilities("Failed to open/query direct libuvc camera: " + describeThrowable(throwable));
        }
    }

    private List<StreamMode> queryStreamModes(String sizeJson) {
        List<StreamMode> modes = new ArrayList<>();
        appendModesForFormat(modes, sizeJson, UVCCamera.FRAME_FORMAT_YUYV);
        appendModesForFormat(modes, sizeJson, UVCCamera.FRAME_FORMAT_MJPEG);
        return modes;
    }

    private void appendModesForFormat(List<StreamMode> modes, String sizeJson, int frameFormat) {
        List<Size> sizes = getSizesForFormat(sizeJson, frameFormat);
        if (sizes == null) return;
        for (Size size : sizes) {
            List<Integer> fpsValues = fpsValuesForSize(size);
            for (Integer fps : fpsValues) modes.add(new StreamMode(frameFormat, size.width, size.height, fps));
        }
    }

    private List<Integer> fpsValuesForSize(Size size) {
        List<Integer> values = new ArrayList<>();
        if (size != null && size.fps != null) {
            for (float rawFps : size.fps) {
                int rounded = Math.round(rawFps);
                if (rounded > 0 && !values.contains(rounded)) values.add(rounded);
            }
        }
        if (values.isEmpty()) values.add(UVCCamera.DEFAULT_PREVIEW_MAX_FPS);
        return values;
    }

    private List<Size> getSizesForFormat(String sizeJson, int frameFormat) {
        if (sizeJson == null || sizeJson.trim().isEmpty()) return null;
        int streamType = frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? STREAM_TYPE_MJPEG : STREAM_TYPE_YUYV;
        try {
            return UVCCamera.getSupportedSize(streamType, sizeJson);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private StreamMode chooseDefaultStreamMode() {
        StreamMode firstMjpeg = null;
        for (StreamMode mode : availableStreamModes) {
            if (mode.frameFormat == UVCCamera.FRAME_FORMAT_YUYV && mode.width == 640 && mode.height == 480) return mode;
            if (mode.frameFormat == UVCCamera.FRAME_FORMAT_MJPEG && firstMjpeg == null) firstMjpeg = mode;
        }
        for (StreamMode mode : availableStreamModes) if (mode.frameFormat == UVCCamera.FRAME_FORMAT_YUYV) return mode;
        return firstMjpeg != null ? firstMjpeg : (availableStreamModes.isEmpty() ? null : availableStreamModes.get(0));
    }

    private StreamMode findEquivalentModeOrDefault(StreamMode requestedMode) {
        for (StreamMode mode : availableStreamModes) {
            if (mode.frameFormat == requestedMode.frameFormat
                    && mode.width == requestedMode.width
                    && mode.height == requestedMode.height
                    && mode.fps == requestedMode.fps) return mode;
        }
        return chooseDefaultStreamMode();
    }

    private void queryControlSupportAndCurrentValues() {
        brightnessSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_CONTRAST);
        gainSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_GAIN);
        exposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE_ABS);
        autoExposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE);
        if (autoExposureSupported) autoExposureEnabled = isAutoExposureCurrentlyEnabled();
        if (exposureSupported) {
            int queriedExposure = getExposurePercentFromDevice();
            if (queriedExposure >= 0) exposurePercent = queriedExposure;
        }
    }

    private boolean checkControlSupport(long supportFlag) {
        try { return activeCamera.checkSupportFlag(supportFlag); } catch (Throwable ignored) { return false; }
    }

    private void applyCameraControls(String reason) {
        applyDisplaySideImageFilter();
        if (activeCamera == null) return;
        try { if (brightnessSupported) activeCamera.setBrightness(brightnessPercent); } catch (Throwable ignored) { }
        try { if (contrastSupported) activeCamera.setContrast(contrastPercent); } catch (Throwable ignored) { }
        try { if (gainSupported) activeCamera.setGain(gainPercent); } catch (Throwable ignored) { }
        if (nativeExposureAccessReady) {
            if (autoExposureSupported) setAutoExposureEnabled(autoExposureEnabled);
            if (exposureSupported && !autoExposureEnabled) setExposurePercentOnDevice(exposurePercent);
        }
        notifyStatus("Camera controls applied: " + reason + ".");
    }

    private void prepareNativeExposureAccess() {
        clearNativeExposureAccess();
        try {
            Class<UVCCamera> cls = UVCCamera.class;
            exposureNativePointerField = accessibleField(cls, "mNativePtr");
            exposureMinimumField = accessibleField(cls, "mExposureMin");
            exposureMaximumField = accessibleField(cls, "mExposureMax");
            exposureDefaultField = accessibleField(cls, "mExposureDef");
            exposureModeDefaultField = accessibleField(cls, "mExposureModeDef");
            updateExposureLimitMethod = accessibleMethod(cls, "nativeUpdateExposureLimit", long.class);
            getExposureMethod = accessibleMethod(cls, "nativeGetExposure", long.class);
            setExposureMethod = accessibleMethod(cls, "nativeSetExposure", long.class, int.class);
            updateExposureModeLimitMethod = accessibleMethod(cls, "nativeUpdateExposureModeLimit", long.class);
            getExposureModeMethod = accessibleMethod(cls, "nativeGetExposureMode", long.class);
            setExposureModeMethod = accessibleMethod(cls, "nativeSetExposureMode", long.class, int.class);
            nativeExposureAccessReady = true;
        } catch (ReflectiveOperationException ignored) {
            clearNativeExposureAccess();
        }
    }

    private void clearNativeExposureAccess() {
        exposureNativePointerField = null;
        exposureMinimumField = null;
        exposureMaximumField = null;
        exposureDefaultField = null;
        exposureModeDefaultField = null;
        updateExposureLimitMethod = null;
        getExposureMethod = null;
        setExposureMethod = null;
        updateExposureModeLimitMethod = null;
        getExposureModeMethod = null;
        setExposureModeMethod = null;
        nativeExposureAccessReady = false;
    }

    private boolean isAutoExposureCurrentlyEnabled() {
        try {
            updateExposureModeLimit();
            int currentMode = (Integer) getExposureModeMethod.invoke(null, nativeCameraPointer());
            return currentMode != MANUAL_EXPOSURE_MODE;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean setAutoExposureEnabled(boolean enabled) {
        try {
            updateExposureModeLimit();
            int requestedMode = enabled ? preferredAutoExposureMode() : MANUAL_EXPOSURE_MODE;
            setExposureModeMethod.invoke(null, nativeCameraPointer(), requestedMode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getExposurePercentFromDevice() {
        try {
            updateExposureLimit();
            int rawExposure = (Integer) getExposureMethod.invoke(null, nativeCameraPointer());
            return rawExposureToPercent(rawExposure);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean setExposurePercentOnDevice(int percent) {
        try {
            updateExposureLimit();
            setExposureMethod.invoke(null, nativeCameraPointer(), percentToRawExposure(percent));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String describeExposureRange() {
        try {
            updateExposureLimit();
            return "raw min=" + exposureMinimumField.getInt(activeCamera)
                    + ", max=" + exposureMaximumField.getInt(activeCamera)
                    + ", def=" + exposureDefaultField.getInt(activeCamera);
        } catch (Throwable ignored) {
            return "raw range unavailable";
        }
    }

    private void updateExposureLimit() throws ReflectiveOperationException {
        updateExposureLimitMethod.invoke(activeCamera, nativeCameraPointer());
    }

    private void updateExposureModeLimit() throws ReflectiveOperationException {
        updateExposureModeLimitMethod.invoke(activeCamera, nativeCameraPointer());
    }

    private long nativeCameraPointer() throws IllegalAccessException {
        return exposureNativePointerField.getLong(activeCamera);
    }

    private int preferredAutoExposureMode() throws IllegalAccessException {
        int defaultMode = exposureModeDefaultField.getInt(activeCamera);
        return defaultMode == MANUAL_EXPOSURE_MODE ? AUTO_EXPOSURE_MODE : defaultMode;
    }

    private int rawExposureToPercent(int rawExposure) throws IllegalAccessException {
        int min = exposureMinimumField.getInt(activeCamera);
        int max = exposureMaximumField.getInt(activeCamera);
        int range = Math.abs(max - min);
        return range <= 0 ? 0 : clamp(Math.round((rawExposure - min) * 100.0f / range), 0, 100);
    }

    private int percentToRawExposure(int percent) throws IllegalAccessException {
        int min = exposureMinimumField.getInt(activeCamera);
        int max = exposureMaximumField.getInt(activeCamera);
        return Math.round(min + (Math.abs(max - min) * clamp(percent, 0, 100) / 100.0f));
    }

    private static Field accessibleField(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method accessibleMethod(Class<?> cls, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = cls.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private void applyDisplaySideImageFilter() {
        float contrastScale = Math.max(0.05f, contrastPercent / 50.0f);
        float brightnessOffset = (brightnessPercent - 50) * 2.55f;
        ColorMatrix colorMatrix = new ColorMatrix();
        if (blackWhiteEnabled) colorMatrix.setSaturation(0.0f);
        colorMatrix.postConcat(new ColorMatrix(new float[]{
                contrastScale, 0, 0, 0, brightnessOffset,
                0, contrastScale, 0, 0, brightnessOffset,
                0, 0, contrastScale, 0, brightnessOffset,
                0, 0, 0, 1, 0
        }));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        previewTextureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        previewTextureView.invalidate();
    }

    private void applyPreviewFitModeLater() {
        previewTextureView.postDelayed(new Runnable() { @Override public void run() { applyPreviewFitMode(); } }, PREVIEW_SURFACE_RETRY_DELAY_MS);
    }

    private void applyPreviewFitMode() {
        previewTextureView.post(new Runnable() { @Override public void run() { applyPreviewFitModeNow(); } });
    }

    private void applyPreviewFitModeNow() {
        int cw = previewContainer.getWidth();
        int ch = previewContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float sourceAspect = activeStreamMode != null ? activeStreamMode.width / (float) activeStreamMode.height : DEFAULT_PREVIEW_ASPECT_RATIO;
        int tw = cw;
        int th = ch;
        if (previewFitMode != PreviewFitMode.STRETCH) {
            boolean containerWide = cw / (float) ch > sourceAspect;
            if (previewFitMode == PreviewFitMode.CONTAIN) {
                if (containerWide) { th = ch; tw = Math.round(th * sourceAspect); }
                else { tw = cw; th = Math.round(tw / sourceAspect); }
            } else {
                if (containerWide) { tw = cw; th = Math.round(tw / sourceAspect); }
                else { th = ch; tw = Math.round(th * sourceAspect); }
            }
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewTextureView.getLayoutParams();
        tw = Math.max(1, tw);
        th = Math.max(1, th);
        if (params.width != tw || params.height != th || params.gravity != Gravity.CENTER) {
            params.width = tw;
            params.height = th;
            params.gravity = Gravity.CENTER;
            previewTextureView.setLayoutParams(params);
        }
        previewTextureView.setTranslationX(0.0f);
        previewTextureView.setTranslationY(0.0f);
        previewTextureView.setScaleX(1.0f);
        previewTextureView.setScaleY(1.0f);
        previewTextureView.setPivotX(tw / 2.0f);
        previewTextureView.setPivotY(th / 2.0f);
    }

    private void scheduleOpenRetry(final UsbDevice device, final USBMonitor.UsbControlBlock controlBlock) {
        if (openRetryScheduled) return;
        openRetryScheduled = true;
        previewTextureView.postDelayed(new Runnable() {
            @Override public void run() {
                openRetryScheduled = false;
                if (previewTextureView.isAvailable() && previewTextureView.getSurfaceTexture() != null) openDirectUvcCamera(device, controlBlock, false);
                else publishCapabilities("Preview surface still unavailable. Preview view size: " + previewTextureView.getWidth() + "×" + previewTextureView.getHeight() + ".");
            }
        }, PREVIEW_SURFACE_RETRY_DELAY_MS);
    }

    private void refreshDetectedUvcDeviceCache() {
        try {
            for (UsbDevice device : usbMonitor.getDeviceList()) if (isUvcVideoDevice(device)) rememberDetectedDevice(device);
        } catch (Throwable throwable) {
            notifyStatus("USB device scan failed: " + describeThrowable(throwable));
        }
    }

    private UvcCapabilities buildCapabilities(String statusText) {
        return new UvcCapabilities(
                activeCamera != null,
                availableStreamModes,
                activeStreamMode,
                brightnessSupported,
                contrastSupported,
                gainSupported,
                exposureSupported,
                autoExposureSupported,
                exposurePercent,
                autoExposureEnabled,
                nativeExposureAccessReady ? describeExposureRange() : "native exposure access unavailable",
                statusText);
    }

    private void publishCapabilities(String statusText) {
        final UvcCapabilities capabilities = buildCapabilities(statusText);
        notifyStatus(statusText);
        mainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onUvcCapabilitiesChanged(capabilities);
            }
        });
    }

    private String describeStreamModes() {
        if (availableStreamModes.isEmpty()) return "No stream modes reported.";
        StringBuilder builder = new StringBuilder("Stream modes:");
        int shown = 0;
        for (StreamMode mode : availableStreamModes) {
            if (shown >= 16) {
                builder.append("\n  …");
                break;
            }
            builder.append("\n  • ").append(mode.fullLabel());
            shown++;
        }
        return builder.toString();
    }

    private void rememberDetectedDevice(UsbDevice device) {
        if (device != null) detectedUvcDevicesById.put(device.getDeviceId(), device);
    }

    private UsbDevice getFirstDetectedUvcDevice() {
        for (UsbDevice device : detectedUvcDevicesById.values()) return device;
        return null;
    }

    private boolean isUvcVideoDevice(UsbDevice device) {
        if (device == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface != null && usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) return true;
        }
        return false;
    }

    private String describeDeviceBrief(UsbDevice device) {
        return String.format(Locale.US, "VID %04x / PID %04x", device.getVendorId(), device.getProductId());
    }

    private String describeDeviceLong(UsbDevice device) {
        return String.format(Locale.US, "VID %04x / PID %04x, interfaces=%d, name=%s",
                device.getVendorId(), device.getProductId(), device.getInterfaceCount(), device.getDeviceName());
    }

    private void notifyStatus(final String statusText) {
        mainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onUvcStatusChanged(statusText);
            }
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + ": "
                + (message == null || message.trim().isEmpty() ? "no detail message" : message);
    }
}
