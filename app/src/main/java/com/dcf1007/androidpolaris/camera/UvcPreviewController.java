package com.dcf1007.androidpolaris.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Direct libuvc backend.
 *
 * <p>Android's Java USB descriptors are not always reliable for UVC cameras: some devices are
 * reported with a null name or even zero Java-visible interfaces, while libuvc can still open the
 * camera after Android grants USB permission. Therefore this controller treats raw USB devices as
 * camera candidates and lets libuvc perform the real validation/query step.</p>
 *
 * <p>The preview lifecycle remains explicit: query capabilities first, select stream type,
 * resolution and FPS, then start preview. Stream settings are changed by closing/reopening the
 * camera, not by mutating preview size on a live stream.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int MANUAL_EXPOSURE_MODE = 1;
    private static final int AUTO_EXPOSURE_MODE = 2;
    private static final int STREAM_TYPE_YUYV = 4;
    private static final int STREAM_TYPE_MJPEG = 6;
    private static final int FALLBACK_FPS = 30;
    private static final long PREVIEW_SURFACE_RETRY_DELAY_MS = 250L;
    private static final float DEFAULT_PREVIEW_ASPECT_RATIO = 4.0f / 3.0f;
    private static final int MAX_DEBUG_LOG_CHARS = 24000;

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

        public String resolutionLabel() { return width + "×" + height; }
        public String fpsLabel() { return fps + " fps"; }
        public String fullLabel() { return resolutionLabel() + " " + formatLabel() + " @ " + fpsLabel(); }
        @Override public String toString() { return fullLabel(); }
    }

    public static final class UvcCapabilities {
        public final boolean cameraOpen;
        public final boolean previewRunning;
        public final List<StreamMode> streamModes;
        public final StreamMode selectedStreamMode;
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

        UvcCapabilities(boolean cameraOpen, boolean previewRunning, List<StreamMode> streamModes,
                        StreamMode selectedStreamMode, StreamMode activeStreamMode,
                        boolean brightnessSupported, boolean contrastSupported, boolean gainSupported,
                        boolean exposureSupported, boolean autoExposureSupported, int exposurePercent,
                        boolean autoExposureEnabled, String exposureRangeText, String statusText) {
            this.cameraOpen = cameraOpen;
            this.previewRunning = previewRunning;
            this.streamModes = Collections.unmodifiableList(new ArrayList<>(streamModes));
            this.selectedStreamMode = selectedStreamMode;
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
    private final UsbManager usbManager;
    private final Map<Integer, UsbDevice> detectedUvcDevicesById = new LinkedHashMap<>();
    private final StringBuilder debugLogBuilder = new StringBuilder();

    private UvcCameraOptionsPanel cameraOptionsPanel;
    private UsbDevice pendingOpenDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private UVCCamera activeCamera;
    private PreviewFitMode previewFitMode = PreviewFitMode.COVER;
    private boolean usbMonitorRegistered;
    private boolean previewStartRetryScheduled;
    private boolean previewStartPendingUntilSurfaceReady;
    private boolean previewRunning;
    private boolean queryInProgress;

    private List<StreamMode> availableStreamModes = new ArrayList<>();
    private StreamMode activeStreamMode;
    private StreamMode requestedStreamMode;

    private int brightnessPercent = 50;
    private int contrastPercent = 50;
    private int gainPercent;
    private int exposurePercent = 50;
    private boolean autoExposureEnabled = true;

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
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        configurePreviewTextureView();
        cameraOptionsPanel = new UvcCameraOptionsPanel(context, this);
        applyPreviewFitModeLater();
        publishCapabilities("UVC backend ready. Connect a USB camera or press Open/query USB UVC camera.");
    }

    public boolean register() {
        if (usbMonitorRegistered) {
            refreshDetectedUsbDeviceCache();
            publishCapabilities("USB monitor already registered. Raw USB candidates: " + detectedUvcDevicesById.size() + ".");
            return true;
        }
        try {
            usbMonitor.register();
            usbMonitorRegistered = true;
            refreshDetectedUsbDeviceCache();
            publishCapabilities("USB monitor registered. Raw USB candidates: " + detectedUvcDevicesById.size() + ".");
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
        try { usbMonitor.unregister(); }
        catch (Throwable throwable) { publishCapabilities("USB monitor unregister warning: " + describeThrowable(throwable)); }
        finally { usbMonitorRegistered = false; }
    }

    public void destroy() {
        closeActiveCamera();
        if (cameraOptionsPanel != null) {
            cameraOptionsPanel.destroy();
            cameraOptionsPanel = null;
        }
        detectedUvcDevicesById.clear();
        try { usbMonitor.destroy(); }
        catch (Throwable throwable) { publishCapabilities("USB monitor destroy warning: " + describeThrowable(throwable)); }
        previewContainer.removeAllViews();
    }

    public boolean hasDetectedUvcDevice() {
        refreshDetectedUsbDeviceCache();
        return !detectedUvcDevicesById.isEmpty();
    }

    /** Requests USB permission for the first raw USB candidate; libuvc validates whether it is UVC. */
    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) return;
        refreshDetectedUsbDeviceCache();
        UsbDevice firstDevice = getFirstDetectedUsbDevice();
        if (firstDevice == null) {
            publishCapabilities("No raw USB device detected. Check OTG power/cable and reconnect the camera.");
            return;
        }
        pendingOpenDevice = firstDevice;
        publishCapabilities("USB candidate detected: " + describeDeviceLong(firstDevice)
                + ". Requesting Android USB permission so libuvc can validate/query it…");
        try {
            if (usbMonitor.requestPermission(firstDevice)) {
                publishCapabilities("USB permission request could not start. Reconnect the camera and try again.");
            }
        } catch (Throwable throwable) {
            publishCapabilities("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public void closeActiveCamera() {
        closeCurrentCameraWithoutPublishing();
        availableStreamModes = new ArrayList<>();
        requestedStreamMode = null;
        brightnessSupported = false;
        contrastSupported = false;
        gainSupported = false;
        exposureSupported = false;
        autoExposureSupported = false;
        publishCapabilities("UVC camera closed.");
    }

    public String describeConnectedUvcDevices() {
        refreshDetectedUsbDeviceCache();
        if (detectedUvcDevicesById.isEmpty()) return "No raw USB devices detected.";
        StringBuilder builder = new StringBuilder();
        builder.append(detectedUvcDevicesById.size()).append(" raw USB candidate(s):\n");
        for (UsbDevice device : detectedUvcDevicesById.values()) builder.append(describeDeviceLong(device)).append('\n');
        if (!availableStreamModes.isEmpty()) {
            builder.append(previewRunning ? "Preview running" : "Capabilities queried; preview stopped").append('\n');
            if (activeStreamMode != null) builder.append("Active stream: ").append(activeStreamMode.fullLabel()).append('\n');
            if (requestedStreamMode != null) builder.append("Selected stream: ").append(requestedStreamMode.fullLabel()).append('\n');
            builder.append("Controls: brightness=").append(brightnessSupported)
                    .append(", contrast=").append(contrastSupported)
                    .append(", gain=").append(gainSupported)
                    .append(", exposure=").append(exposureSupported)
                    .append(", auto exposure=").append(autoExposureSupported).append('\n')
                    .append(describeStreamModes());
        }
        return builder.toString().trim();
    }

    public UvcCapabilities getCurrentCapabilities() {
        return buildCapabilities(activeCamera == null ? "No UVC camera open." : previewRunning ? "UVC preview running." : "UVC capabilities queried; preview not started.");
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    public void selectStreamMode(StreamMode streamMode) {
        if (streamMode == null) return;
        requestedStreamMode = findEquivalentModeOrDefault(streamMode);
        if (requestedStreamMode == null) return;
        String action = previewRunning ? "Stop preview before changing stream." : "Press Start selected stream to open preview.";
        publishCapabilities("Selected UVC stream: " + requestedStreamMode.fullLabel() + ". " + action);
    }

    void startSelectedStream() {
        if (activeControlBlock == null || pendingOpenDevice == null) {
            publishCapabilities("No permitted UVC camera is available. Press Open/query USB UVC camera first.");
            return;
        }
        if (requestedStreamMode == null) requestedStreamMode = chooseDefaultStreamMode();
        if (requestedStreamMode == null) {
            publishCapabilities("No UVC stream mode is available to start.");
            return;
        }
        if (!previewTextureView.isAvailable() || previewTextureView.getSurfaceTexture() == null) {
            previewStartPendingUntilSurfaceReady = true;
            schedulePreviewStartRetry();
            publishCapabilities("Preview surface is not ready. Selected stream will start when the surface is available.");
            return;
        }
        startPreviewByReopeningCamera(requestedStreamMode);
    }

    public void setCameraControls(int brightnessPercent, int contrastPercent, int gainPercent,
                                  int exposurePercent, boolean autoExposureEnabled) {
        this.brightnessPercent = clamp(brightnessPercent, 0, 100);
        this.contrastPercent = clamp(contrastPercent, 0, 100);
        this.gainPercent = clamp(gainPercent, 0, 100);
        this.exposurePercent = clamp(exposurePercent, 0, 100);
        this.autoExposureEnabled = autoExposureEnabled;
        applyCameraControls("camera option changed");
    }

    void saveDebugLogToDownloads() {
        try { notifyStatus("UVC debug log saved to " + UvcLogStorage.saveLogFile(context, buildDebugExportText()) + "."); }
        catch (Throwable throwable) { notifyStatus("Failed to save UVC log file: " + describeThrowable(throwable)); }
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
                if (previewStartPendingUntilSurfaceReady) startSelectedStream();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { applyPreviewFitMode(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) { closeActiveCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });
    }

    private USBMonitor.OnDeviceConnectListener createUsbConnectionListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) {
                rememberDetectedDevice(device);
                if (device != null) publishCapabilities("USB attached: " + describeDeviceLong(device) + ".");
            }
            @Override public void onDetach(UsbDevice device) {
                if (device != null) detectedUvcDevicesById.remove(device.getDeviceId());
                closeActiveCamera();
                publishCapabilities("USB detached" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock, boolean createNew) {
                if (device == null || controlBlock == null) return;
                rememberDetectedDevice(device);
                pendingOpenDevice = device;
                activeControlBlock = controlBlock;
                queryCapabilitiesOnly(device, controlBlock);
            }
            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                closeActiveCamera();
                publishCapabilities("USB disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onCancel(UsbDevice device) {
                queryInProgress = false;
                pendingOpenDevice = null;
                publishCapabilities("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
            }
        };
    }

    private void queryCapabilitiesOnly(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
        if (queryInProgress) {
            publishCapabilities("UVC query already in progress; ignoring duplicate request.");
            return;
        }
        queryInProgress = true;
        closeCurrentCameraWithoutPublishing();
        try {
            publishCapabilities("libuvc validating/querying USB candidate: " + describeDeviceLong(device) + "…");
            UVCCamera camera = new UVCCamera();
            camera.open(controlBlock);
            activeCamera = camera;
            activeControlBlock = controlBlock;
            pendingOpenDevice = device;
            previewRunning = false;
            activeStreamMode = null;
            availableStreamModes = queryStreamModes(camera, camera.getSupportedSize());
            requestedStreamMode = findEquivalentModeOrDefault(requestedStreamMode);
            queryDeviceCapabilitiesAfterOpen(camera);
            applyCameraControls("capability query");
            publishCapabilities("UVC capabilities queried by libuvc. Select stream type/resolution/FPS, then press Start selected stream.");
        } catch (Throwable throwable) {
            closeCurrentCameraWithoutPublishing();
            publishCapabilities("libuvc rejected or failed to query this USB device: " + describeThrowable(throwable));
        } finally {
            queryInProgress = false;
        }
    }

    private void startPreviewByReopeningCamera(StreamMode requestedMode) {
        previewStartPendingUntilSurfaceReady = false;
        publishCapabilities((previewRunning ? "Reopening" : "Starting") + " UVC preview at " + requestedMode.fullLabel() + "…");
        closeCurrentCameraWithoutPublishing();
        try {
            UVCCamera camera = new UVCCamera();
            camera.open(activeControlBlock);
            activeCamera = camera;
            availableStreamModes = queryStreamModes(camera, camera.getSupportedSize());
            requestedStreamMode = findEquivalentModeOrDefault(requestedMode);
            if (requestedStreamMode == null) throw new IllegalStateException("Selected UVC stream mode is no longer available");
            startPreviewWithMode(camera, requestedStreamMode);
            activeStreamMode = requestedStreamMode;
            previewRunning = true;
            queryDeviceCapabilitiesAfterOpen(camera);
            applyCameraControls("preview started");
            applyPreviewFitMode();
            publishCapabilities("UVC preview running at " + activeStreamMode.fullLabel() + ".");
        } catch (Throwable throwable) {
            closeCurrentCameraWithoutPublishing();
            publishCapabilities("Failed to start selected UVC stream: " + describeThrowable(throwable));
        }
    }

    private void closeCurrentCameraWithoutPublishing() {
        try { if (activeCamera != null) activeCamera.destroy(); }
        catch (Throwable throwable) { notifyStatus("Direct libuvc close warning: " + describeThrowable(throwable)); }
        finally {
            activeCamera = null;
            activeStreamMode = null;
            previewRunning = false;
            previewStartRetryScheduled = false;
            previewStartPendingUntilSurfaceReady = false;
            clearNativeExposureAccess();
        }
    }

    private void startPreviewWithMode(UVCCamera camera, StreamMode mode) {
        camera.setPreviewSize(mode.width, mode.height, mode.fps, mode.fps, mode.frameFormat, UVCCamera.DEFAULT_BANDWIDTH);
        camera.setPreviewTexture(previewTextureView.getSurfaceTexture());
        camera.startPreview();
        camera.updateCameraParams();
    }

    private void queryDeviceCapabilitiesAfterOpen(UVCCamera camera) {
        prepareNativeExposureAccess();
        brightnessSupported = camera != null && checkControlSupport(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = camera != null && checkControlSupport(UVCCamera.PU_CONTRAST);
        gainSupported = camera != null && checkControlSupport(UVCCamera.PU_GAIN);
        exposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE_ABS);
        autoExposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE);
        if (autoExposureSupported) autoExposureEnabled = isAutoExposureCurrentlyEnabled();
        if (exposureSupported) {
            int queriedExposure = getExposurePercentFromDevice();
            if (queriedExposure >= 0) exposurePercent = queriedExposure;
        }
    }

    private List<StreamMode> queryStreamModes(UVCCamera camera, String sizeJson) {
        List<StreamMode> modes = new ArrayList<>();
        appendModesForFormat(modes, camera, sizeJson, UVCCamera.FRAME_FORMAT_YUYV);
        appendModesForFormat(modes, camera, sizeJson, UVCCamera.FRAME_FORMAT_MJPEG);
        return modes;
    }

    private void appendModesForFormat(List<StreamMode> modes, UVCCamera camera, String sizeJson, int frameFormat) {
        List<Size> sizes = getSizesForFormat(camera, sizeJson, frameFormat);
        if (sizes == null) return;
        for (Size size : sizes) for (Integer fps : fpsValuesForSize(size)) modes.add(new StreamMode(frameFormat, size.width, size.height, fps));
    }

    private List<Integer> fpsValuesForSize(Size size) {
        List<Integer> values = new ArrayList<>();
        if (size != null && size.fps != null) {
            for (float rawFps : size.fps) {
                int rounded = Math.round(rawFps);
                if (rounded > 0 && !values.contains(rounded)) values.add(rounded);
            }
        }
        if (values.isEmpty()) values.add(FALLBACK_FPS);
        return values;
    }

    private List<Size> getSizesForFormat(UVCCamera camera, String sizeJson, int frameFormat) {
        if (camera == null || sizeJson == null || sizeJson.trim().isEmpty()) return null;
        int streamType = frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? STREAM_TYPE_MJPEG : STREAM_TYPE_YUYV;
        try { return camera.getSupportedSize(streamType, sizeJson); }
        catch (Throwable ignored) { return null; }
    }

    private StreamMode chooseDefaultStreamMode() {
        StreamMode bestUncompressed = bestModeForFormat(UVCCamera.FRAME_FORMAT_YUYV);
        return bestUncompressed != null ? bestUncompressed : bestModeForFormat(UVCCamera.FRAME_FORMAT_MJPEG);
    }

    private StreamMode bestModeForFormat(int frameFormat) {
        StreamMode best = null;
        for (StreamMode mode : availableStreamModes) {
            if (mode.frameFormat != frameFormat) continue;
            if (best == null || streamModeRank(mode) > streamModeRank(best)) best = mode;
        }
        return best;
    }

    private long streamModeRank(StreamMode mode) { return (long) mode.width * (long) mode.height * 10000L + mode.fps; }

    private StreamMode findEquivalentModeOrDefault(StreamMode requestedMode) {
        if (requestedMode != null) for (StreamMode mode : availableStreamModes) if (sameStreamMode(mode, requestedMode)) return mode;
        return chooseDefaultStreamMode();
    }

    private boolean sameStreamMode(StreamMode first, StreamMode second) {
        return first != null && second != null && first.frameFormat == second.frameFormat && first.width == second.width && first.height == second.height && first.fps == second.fps;
    }

    private boolean checkControlSupport(long supportFlag) {
        try { return activeCamera.checkSupportFlag(supportFlag); } catch (Throwable ignored) { return false; }
    }

    private void applyCameraControls(String reason) {
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
        } catch (ReflectiveOperationException ignored) { clearNativeExposureAccess(); }
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
        } catch (Throwable ignored) { return true; }
    }

    private boolean setAutoExposureEnabled(boolean enabled) {
        try {
            updateExposureModeLimit();
            setExposureModeMethod.invoke(null, nativeCameraPointer(), enabled ? preferredAutoExposureMode() : MANUAL_EXPOSURE_MODE);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private int getExposurePercentFromDevice() {
        try {
            updateExposureLimit();
            return rawExposureToPercent((Integer) getExposureMethod.invoke(null, nativeCameraPointer()));
        } catch (Throwable ignored) { return -1; }
    }

    private boolean setExposurePercentOnDevice(int percent) {
        try {
            updateExposureLimit();
            setExposureMethod.invoke(null, nativeCameraPointer(), percentToRawExposure(percent));
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private String describeExposureRange() {
        try {
            updateExposureLimit();
            return "raw min=" + exposureMinimumField.getInt(activeCamera)
                    + ", max=" + exposureMaximumField.getInt(activeCamera)
                    + ", def=" + exposureDefaultField.getInt(activeCamera);
        } catch (Throwable ignored) { return "raw range unavailable"; }
    }

    private void updateExposureLimit() throws ReflectiveOperationException { updateExposureLimitMethod.invoke(activeCamera, nativeCameraPointer()); }
    private void updateExposureModeLimit() throws ReflectiveOperationException { updateExposureModeLimitMethod.invoke(activeCamera, nativeCameraPointer()); }
    private long nativeCameraPointer() throws IllegalAccessException { return exposureNativePointerField.getLong(activeCamera); }
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

    private void applyPreviewFitModeLater() { previewTextureView.postDelayed(new Runnable() { @Override public void run() { applyPreviewFitMode(); } }, PREVIEW_SURFACE_RETRY_DELAY_MS); }
    private void applyPreviewFitMode() { previewTextureView.post(new Runnable() { @Override public void run() { applyPreviewFitModeNow(); } }); }

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

    private void schedulePreviewStartRetry() {
        if (previewStartRetryScheduled) return;
        previewStartRetryScheduled = true;
        previewTextureView.postDelayed(new Runnable() {
            @Override public void run() {
                previewStartRetryScheduled = false;
                if (previewStartPendingUntilSurfaceReady) startSelectedStream();
            }
        }, PREVIEW_SURFACE_RETRY_DELAY_MS);
    }

    private void refreshDetectedUsbDeviceCache() {
        try {
            detectedUvcDevicesById.clear();
            for (UsbDevice device : usbMonitor.getDeviceList()) rememberDetectedDevice(device);
            if (usbManager != null) for (UsbDevice device : usbManager.getDeviceList().values()) rememberDetectedDevice(device);
        } catch (Throwable throwable) { notifyStatus("USB device scan failed: " + describeThrowable(throwable)); }
    }

    private UvcCapabilities buildCapabilities(String statusText) {
        return new UvcCapabilities(activeCamera != null, previewRunning, availableStreamModes, requestedStreamMode,
                activeStreamMode, brightnessSupported, contrastSupported, gainSupported, exposureSupported,
                autoExposureSupported, exposurePercent, autoExposureEnabled,
                nativeExposureAccessReady ? describeExposureRange() : "native exposure access unavailable", statusText);
    }

    private void publishCapabilities(String statusText) {
        final UvcCapabilities capabilities = buildCapabilities(statusText);
        notifyStatus(statusText);
        mainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (cameraOptionsPanel != null) cameraOptionsPanel.update(capabilities);
                if (listener != null) listener.onUvcCapabilitiesChanged(capabilities);
            }
        });
    }

    private String describeStreamModes() {
        if (availableStreamModes.isEmpty()) return "No stream modes reported.";
        StringBuilder builder = new StringBuilder("Stream modes:");
        int shown = 0;
        for (StreamMode mode : availableStreamModes) {
            if (shown >= 24) { builder.append("\n  …"); break; }
            builder.append("\n  • ").append(mode.fullLabel());
            shown++;
        }
        return builder.toString();
    }

    private String buildDebugExportText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Android Polaris UVC debug log\n");
        builder.append("Generated: ").append(String.format(Locale.US, "%tF %<tT", new Date())).append('\n');
        builder.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append('\n');
        builder.append('\n').append("Current UVC state:\n").append(describeConnectedUvcDevices()).append('\n');
        builder.append('\n').append("Log:\n")
                .append(debugLogBuilder.length() == 0 ? "No UVC log lines recorded." : debugLogBuilder.toString().trim());
        return builder.toString();
    }

    private void rememberDetectedDevice(UsbDevice device) { if (device != null) detectedUvcDevicesById.put(device.getDeviceId(), device); }
    private UsbDevice getFirstDetectedUsbDevice() { for (UsbDevice device : detectedUvcDevicesById.values()) return device; return null; }

    private String describeDeviceBrief(UsbDevice device) {
        return String.format(Locale.US, "VID %04x / PID %04x", device.getVendorId(), device.getProductId());
    }

    private String describeDeviceLong(UsbDevice device) {
        StringBuilder builder = new StringBuilder();
        builder.append(describeDeviceBrief(device))
                .append(", class=").append(device.getDeviceClass()).append('/').append(device.getDeviceSubclass()).append('/').append(device.getDeviceProtocol())
                .append(", interfaces=").append(device.getInterfaceCount())
                .append(", name=").append(device.getDeviceName());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface == null) continue;
            builder.append("; if").append(i).append('=')
                    .append(usbInterface.getInterfaceClass()).append('/')
                    .append(usbInterface.getInterfaceSubclass()).append('/')
                    .append(usbInterface.getInterfaceProtocol());
            if (usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) builder.append(" USB_VIDEO");
        }
        return builder.toString();
    }

    private void notifyStatus(final String statusText) {
        recordDebugLine(statusText);
        mainThreadHandler.post(new Runnable() { @Override public void run() { if (listener != null) listener.onUvcStatusChanged(statusText); } });
    }

    private void recordDebugLine(String message) {
        if (message == null || message.trim().isEmpty()) return;
        debugLogBuilder.append(String.format(Locale.US, "[%tF %<tT] %s\n", new Date(), message.trim()));
        if (debugLogBuilder.length() > MAX_DEBUG_LOG_CHARS) debugLogBuilder.delete(0, debugLogBuilder.length() - MAX_DEBUG_LOG_CHARS);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + ": " + (message == null || message.trim().isEmpty() ? "no detail message" : message);
    }
}
