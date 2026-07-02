package com.dcf1007.androidpolaris.backend;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

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
 * Backend for the camera hardware panel.
 *
 * <p>This class owns execution only: USB discovery, Android USB permission, libuvc open/query,
 * stream-mode probing, preview start/stop, hardware controls and UVC debug export. It never creates
 * buttons, textboxes or panels. The UI controller calls these methods and renders returned state.</p>
 */
public final class CameraHardwareBackend {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int STREAM_TYPE_YUYV = 4;
    private static final int STREAM_TYPE_MJPEG = 6;
    private static final int FALLBACK_FPS = 30;
    private static final int MANUAL_EXPOSURE_MODE = 1;
    private static final int AUTO_EXPOSURE_MODE = 2;
    private static final int FIXED_FRAME_RATE_EXPOSURE_PRIORITY = 0;
    private static final int[] FPS_PROBE_CANDIDATES = {120, 100, 60, 50, 30, 25, 20, 15, 10, 5, 1};
    private static final int MAX_LOG_CHARS = 24000;

    public enum PreviewFitMode { COVER, CONTAIN, STRETCH }

    public interface Listener {
        void onCameraStatusChanged(String statusText);
        void onCameraCapabilitiesChanged(Capabilities capabilities);
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

        public String formatLabel() { return frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG / compressed" : "YUYV / uncompressed"; }
        public String resolutionLabel() { return width + "×" + height; }
        public String fpsLabel() { return fps + " fps"; }
        public String fullLabel() { return resolutionLabel() + " " + formatLabel() + " @ " + fpsLabel(); }
        @Override public String toString() { return fullLabel(); }
    }

    public static final class Capabilities {
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

        Capabilities(boolean cameraOpen, boolean previewRunning, List<StreamMode> streamModes,
                     StreamMode selectedStreamMode, StreamMode activeStreamMode, boolean brightnessSupported,
                     boolean contrastSupported, boolean gainSupported, boolean exposureSupported,
                     boolean autoExposureSupported, int exposurePercent, boolean autoExposureEnabled,
                     String exposureRangeText, String statusText) {
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
    private final TextureView previewTextureView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final UsbManager usbManager;
    private final USBMonitor usbMonitor;
    private final Map<Integer, UsbDevice> rawUsbDevicesById = new LinkedHashMap<>();
    private final StringBuilder debugLog = new StringBuilder();
    private final DebugLogBackend logStorage = new DebugLogBackend();

    private UsbDevice permittedUsbDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private UVCCamera activeCamera;
    private boolean usbMonitorRegistered;
    private boolean queryInProgress;
    private boolean previewRunning;
    private boolean startWhenSurfaceAvailable;

    private List<StreamMode> availableStreamModes = new ArrayList<>();
    private StreamMode selectedStreamMode;
    private StreamMode activeStreamMode;

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
    private boolean exposurePrioritySupported;

    private Field nativePointerField, exposureMinField, exposureMaxField, exposureDefField, exposureModeDefField;
    private Method updateExposureLimitMethod, getExposureMethod, setExposureMethod;
    private Method updateExposureModeLimitMethod, getExposureModeMethod, setExposureModeMethod;
    private Method updateExposurePriorityLimitMethod, getExposurePriorityMethod, setExposurePriorityMethod;
    private boolean nativeExposureReady;

    public CameraHardwareBackend(Context context, TextureView previewTextureView, Listener listener) {
        this.context = context;
        this.previewTextureView = previewTextureView;
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.usbMonitor = new USBMonitor(context, createConnectionListener());
        this.previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (startWhenSurfaceAvailable) startSelectedStream();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        publish("UVC backend ready. Press Refresh USB devices or connect a USB camera.");
    }

    public boolean registerUsbMonitor() {
        if (usbMonitorRegistered) {
            scanRawUsbDevices();
            publish("USB monitor already registered. Raw USB candidates: " + rawUsbDevicesById.size() + ".");
            return true;
        }
        try {
            usbMonitor.register();
            usbMonitorRegistered = true;
            scanRawUsbDevices();
            publish("USB monitor registered. Raw USB candidates: " + rawUsbDevicesById.size() + ".");
            return true;
        } catch (Throwable throwable) {
            usbMonitorRegistered = false;
            publish("USB monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    public void unregisterUsbMonitor() {
        closeCamera();
        if (!usbMonitorRegistered) return;
        try { usbMonitor.unregister(); }
        catch (Throwable throwable) { publish("USB monitor unregister warning: " + describeThrowable(throwable)); }
        finally { usbMonitorRegistered = false; }
    }

    public void destroy() {
        closeCamera();
        rawUsbDevicesById.clear();
        try { usbMonitor.destroy(); }
        catch (Throwable throwable) { publish("USB monitor destroy warning: " + describeThrowable(throwable)); }
    }

    public void refreshUsbDevices() {
        if (queryInProgress) { publish("USB refresh ignored because a query is already in progress."); return; }
        scanRawUsbDevices();
        if (previewRunning) { publish("USB refresh ignored while stream is running. Stop stream before querying another device."); return; }
        publish("Refreshing USB devices. Raw USB candidates: " + rawUsbDevicesById.size() + ".");
        requestPermissionAndOpenFirstCamera();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!registerUsbMonitor()) return;
        scanRawUsbDevices();
        UsbDevice firstDevice = firstRawUsbDevice();
        if (firstDevice == null) { publish("No raw USB device detected. Check OTG power/cable and reconnect the camera."); return; }
        permittedUsbDevice = firstDevice;
        publish("USB candidate detected: " + describeDeviceLong(firstDevice) + ". Requesting Android USB permission so libuvc can validate/query it…");
        try {
            if (usbMonitor.requestPermission(firstDevice)) publish("USB permission request could not start. Reconnect the camera and try again.");
        } catch (Throwable throwable) {
            publish("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public void selectStreamMode(StreamMode requestedMode) {
        if (requestedMode == null) return;
        selectedStreamMode = equivalentModeOrDefault(requestedMode);
        if (selectedStreamMode != null) publish("Selected UVC stream: " + selectedStreamMode.fullLabel() + ". " + (previewRunning ? "Stop stream before changing stream settings." : "Press Start stream to open preview."));
    }

    public void startSelectedStream() {
        if (queryInProgress) { publish("UVC query is still in progress. Wait for stream modes to finish loading."); return; }
        if (activeControlBlock == null || permittedUsbDevice == null || activeCamera == null) { publish("No permitted UVC camera is available. Press Refresh USB devices first."); return; }
        if (selectedStreamMode == null) selectedStreamMode = chooseDefaultMode();
        if (selectedStreamMode == null) { publish("No UVC stream mode is available to start."); return; }
        if (!previewTextureView.isAvailable() || previewTextureView.getSurfaceTexture() == null) {
            startWhenSurfaceAvailable = true;
            publish("Preview surface is not ready. Selected stream will start when the surface is available.");
            return;
        }
        try {
            startPreviewOnOpenCamera(selectedStreamMode);
        } catch (Throwable firstFailure) {
            statusOnly("Fast stream start failed; reopening camera once without re-query: " + describeThrowable(firstFailure));
            try {
                reopenWithoutRequery();
                startPreviewOnOpenCamera(selectedStreamMode);
            } catch (Throwable secondFailure) {
                destroyActiveCameraOnly();
                publish("Failed to start selected UVC stream: " + describeThrowable(secondFailure));
            }
        }
    }

    public void stopStream() {
        if (activeCamera == null) { publish("No queried UVC camera is open. Press Refresh USB devices first."); return; }
        if (!previewRunning) { activeStreamMode = null; publish("UVC stream is already stopped. Stream type, resolution and FPS controls are unlocked."); return; }
        try { activeCamera.stopPreview(); }
        catch (Throwable throwable) { statusOnly("Stop stream warning: " + describeThrowable(throwable)); }
        finally {
            previewRunning = false;
            activeStreamMode = null;
            startWhenSurfaceAvailable = false;
            publish("UVC stream stopped. Camera remains queried; stream type, resolution and FPS controls are unlocked.");
        }
    }

    public void setCameraControls(int brightness, int contrast, int gain, int exposure, boolean autoExposure) {
        brightnessPercent = clamp(brightness, 0, 100);
        contrastPercent = clamp(contrast, 0, 100);
        gainPercent = clamp(gain, 0, 100);
        exposurePercent = clamp(exposure, 0, 100);
        autoExposureEnabled = autoExposure;
        applyCameraControls("camera option changed");
    }

    public void saveDebugLogToDownloads() {
        try { statusOnly("UVC debug log saved to " + logStorage.saveTextLogToDownloads(context, "android_polaris_uvc_debug_log", buildDebugExportText()) + "."); }
        catch (Throwable throwable) { statusOnly("Failed to save UVC log file: " + describeThrowable(throwable)); }
    }

    public Capabilities currentCapabilities() {
        return buildCapabilities(activeCamera == null ? "No UVC camera open." : previewRunning ? "UVC preview running." : "UVC capabilities queried; preview not started.");
    }

    public String describeConnectedDevices() {
        scanRawUsbDevices();
        if (rawUsbDevicesById.isEmpty()) return "No raw USB devices detected.";
        StringBuilder builder = new StringBuilder();
        builder.append(rawUsbDevicesById.size()).append(" raw USB candidate(s):\n");
        for (UsbDevice device : rawUsbDevicesById.values()) builder.append(describeDeviceLong(device)).append('\n');
        if (!availableStreamModes.isEmpty()) {
            builder.append(previewRunning ? "Preview running" : "Capabilities queried; preview stopped").append('\n');
            if (activeStreamMode != null) builder.append("Active stream: ").append(activeStreamMode.fullLabel()).append('\n');
            if (selectedStreamMode != null) builder.append("Selected stream: ").append(selectedStreamMode.fullLabel()).append('\n');
            builder.append(describeStreamModes());
        }
        return builder.toString().trim();
    }

    private USBMonitor.OnDeviceConnectListener createConnectionListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) { rememberDevice(device); if (device != null) publish("USB attached: " + describeDeviceLong(device) + "."); }
            @Override public void onDetach(UsbDevice device) { if (device != null) rawUsbDevicesById.remove(device.getDeviceId()); closeCamera(); publish("USB detached" + (device == null ? "." : ": " + device.getDeviceName())); }
            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock, boolean createNew) { if (device != null && controlBlock != null) { rememberDevice(device); permittedUsbDevice = device; activeControlBlock = controlBlock; queryCapabilities(device, controlBlock); } }
            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) { closeCamera(); publish("USB disconnected" + (device == null ? "." : ": " + device.getDeviceName())); }
            @Override public void onCancel(UsbDevice device) { queryInProgress = false; permittedUsbDevice = null; publish("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + ".")); }
        };
    }

    private void queryCapabilities(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
        if (queryInProgress) { publish("UVC query already in progress; ignoring duplicate request."); return; }
        queryInProgress = true;
        destroyActiveCameraOnly();
        try {
            publish("libuvc validating/querying USB candidate: " + describeDeviceLong(device) + "…");
            activeCamera = new UVCCamera();
            activeCamera.open(controlBlock);
            activeControlBlock = controlBlock;
            permittedUsbDevice = device;
            String supportedSizeJson = activeCamera.getSupportedSize();
            availableStreamModes = queryStreamModes(activeCamera, supportedSizeJson);
            selectedStreamMode = equivalentModeOrDefault(selectedStreamMode);
            queryControlSupport(activeCamera);
            applyCameraControls("capability query");
            publish("UVC capabilities queried by libuvc. Select stream type/resolution/FPS, then press Start stream.");
        } catch (Throwable throwable) {
            destroyActiveCameraOnly();
            publish("libuvc rejected or failed to query this USB device: " + describeThrowable(throwable));
        } finally {
            queryInProgress = false;
        }
    }

    private void startPreviewOnOpenCamera(StreamMode mode) {
        startWhenSurfaceAvailable = false;
        selectedStreamMode = equivalentModeOrDefault(mode);
        if (selectedStreamMode == null) throw new IllegalStateException("Selected UVC stream mode is no longer available");
        activeCamera.setPreviewSize(selectedStreamMode.width, selectedStreamMode.height, selectedStreamMode.fps, selectedStreamMode.fps, selectedStreamMode.frameFormat, UVCCamera.DEFAULT_BANDWIDTH);
        activeCamera.setPreviewTexture(previewTextureView.getSurfaceTexture());
        activeCamera.startPreview();
        activeCamera.updateCameraParams();
        activeStreamMode = selectedStreamMode;
        previewRunning = true;
        applyCameraControls("preview started");
        publish("UVC preview running at " + activeStreamMode.fullLabel() + ".");
    }

    private void reopenWithoutRequery() {
        destroyActiveCameraOnly();
        if (activeControlBlock == null) throw new IllegalStateException("USB control block is not available");
        activeCamera = new UVCCamera();
        activeCamera.open(activeControlBlock);
        queryControlSupport(activeCamera);
    }

    private void closeCamera() {
        destroyActiveCameraOnly();
        activeControlBlock = null;
        permittedUsbDevice = null;
        availableStreamModes = new ArrayList<>();
        selectedStreamMode = null;
        clearControlFlags();
        publish("UVC camera closed.");
    }

    private void destroyActiveCameraOnly() {
        try { if (activeCamera != null) activeCamera.destroy(); }
        catch (Throwable throwable) { statusOnly("Direct libuvc close warning: " + describeThrowable(throwable)); }
        finally { activeCamera = null; activeStreamMode = null; previewRunning = false; startWhenSurfaceAvailable = false; clearNativeExposureAccess(); }
    }

    private List<StreamMode> queryStreamModes(UVCCamera camera, String sizeJson) {
        List<StreamMode> base = queryBaseStreamModes(camera, sizeJson);
        List<StreamMode> probed = probeExactFpsModes(camera, base);
        if (!probed.isEmpty()) { statusOnly("Exact FPS probe found " + probed.size() + " stream mode(s) from " + base.size() + " base entries."); return probed; }
        statusOnly("Exact FPS probe found no modes; using libuvc base sizes with fallback FPS.");
        return base;
    }

    private List<StreamMode> queryBaseStreamModes(UVCCamera camera, String sizeJson) {
        List<StreamMode> modes = new ArrayList<>();
        appendBaseModes(modes, camera, sizeJson, UVCCamera.FRAME_FORMAT_YUYV);
        appendBaseModes(modes, camera, sizeJson, UVCCamera.FRAME_FORMAT_MJPEG);
        return modes;
    }

    private void appendBaseModes(List<StreamMode> modes, UVCCamera camera, String sizeJson, int frameFormat) {
        List<Size> sizes = sizesForFormat(camera, sizeJson, frameFormat);
        if (sizes == null) return;
        for (Size size : sizes) addUniqueMode(modes, new StreamMode(frameFormat, size.width, size.height, FALLBACK_FPS));
    }

    private List<Size> sizesForFormat(UVCCamera camera, String sizeJson, int frameFormat) {
        int streamType = frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? STREAM_TYPE_MJPEG : STREAM_TYPE_YUYV;
        try { return camera.getSupportedSize(streamType, sizeJson); }
        catch (Throwable ignored) { return null; }
    }

    private List<StreamMode> probeExactFpsModes(UVCCamera camera, List<StreamMode> baseModes) {
        List<StreamMode> modes = new ArrayList<>();
        for (StreamMode base : baseModes) {
            for (int fps : FPS_PROBE_CANDIDATES) {
                if (isExactModeAccepted(camera, base, fps, baseModes)) addUniqueMode(modes, new StreamMode(base.frameFormat, base.width, base.height, fps));
            }
        }
        return modes;
    }

    private boolean isExactModeAccepted(UVCCamera camera, StreamMode base, int fps, List<StreamMode> baseModes) {
        StreamMode separator = probeSeparator(base, baseModes);
        if (separator != null) {
            try { camera.setPreviewSize(separator.width, separator.height, separator.fps, separator.fps, separator.frameFormat, UVCCamera.DEFAULT_BANDWIDTH); }
            catch (Throwable ignored) { }
        }
        try { camera.setPreviewSize(base.width, base.height, fps, fps, base.frameFormat, UVCCamera.DEFAULT_BANDWIDTH); return true; }
        catch (Throwable ignored) { return false; }
    }

    private StreamMode probeSeparator(StreamMode target, List<StreamMode> baseModes) {
        for (StreamMode candidate : baseModes) if (candidate.width != target.width || candidate.height != target.height || candidate.frameFormat != target.frameFormat) return candidate;
        return null;
    }

    private void queryControlSupport(UVCCamera camera) {
        prepareNativeExposureAccess();
        brightnessSupported = camera != null && support(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = camera != null && support(UVCCamera.PU_CONTRAST);
        gainSupported = camera != null && support(UVCCamera.PU_GAIN);
        exposureSupported = nativeExposureReady && support(UVCCamera.CTRL_AE_ABS);
        autoExposureSupported = nativeExposureReady && support(UVCCamera.CTRL_AE);
        exposurePrioritySupported = nativeExposureReady && support(UVCCamera.CTRL_AE_PRIORITY);
        if (autoExposureSupported) autoExposureEnabled = isAutoExposureCurrentlyEnabled();
        if (exposureSupported) { int percent = exposurePercentFromDevice(); if (percent >= 0) exposurePercent = percent; }
    }

    private boolean support(long flag) { try { return activeCamera.checkSupportFlag(flag); } catch (Throwable ignored) { return false; } }

    private void applyCameraControls(String reason) {
        if (activeCamera == null) return;
        try { if (brightnessSupported) activeCamera.setBrightness(brightnessPercent); } catch (Throwable ignored) { }
        try { if (contrastSupported) activeCamera.setContrast(contrastPercent); } catch (Throwable ignored) { }
        try { if (gainSupported) activeCamera.setGain(gainPercent); } catch (Throwable ignored) { }
        if (nativeExposureReady) {
            if (autoExposureSupported) setAutoExposureEnabled(autoExposureEnabled);
            if (!autoExposureEnabled && exposurePrioritySupported) setFixedFrameRateExposurePriority();
            if (!autoExposureEnabled && exposureSupported) setExposurePercentOnDevice(exposurePercent);
        }
        statusOnly("Camera controls applied: " + reason + ". " + describeExposureState());
    }

    private void prepareNativeExposureAccess() {
        clearNativeExposureAccess();
        try {
            Class<UVCCamera> cls = UVCCamera.class;
            nativePointerField = field(cls, "mNativePtr");
            exposureMinField = field(cls, "mExposureMin");
            exposureMaxField = field(cls, "mExposureMax");
            exposureDefField = field(cls, "mExposureDef");
            exposureModeDefField = field(cls, "mExposureModeDef");
            updateExposureLimitMethod = method(cls, "nativeUpdateExposureLimit", long.class);
            getExposureMethod = method(cls, "nativeGetExposure", long.class);
            setExposureMethod = method(cls, "nativeSetExposure", long.class, int.class);
            updateExposureModeLimitMethod = method(cls, "nativeUpdateExposureModeLimit", long.class);
            getExposureModeMethod = method(cls, "nativeGetExposureMode", long.class);
            setExposureModeMethod = method(cls, "nativeSetExposureMode", long.class, int.class);
            updateExposurePriorityLimitMethod = method(cls, "nativeUpdateExposurePriorityLimit", long.class);
            getExposurePriorityMethod = method(cls, "nativeGetExposurePriority", long.class);
            setExposurePriorityMethod = method(cls, "nativeSetExposurePriority", long.class, int.class);
            nativeExposureReady = true;
        } catch (ReflectiveOperationException ignored) { clearNativeExposureAccess(); }
    }

    private void clearNativeExposureAccess() {
        nativePointerField = exposureMinField = exposureMaxField = exposureDefField = exposureModeDefField = null;
        updateExposureLimitMethod = getExposureMethod = setExposureMethod = null;
        updateExposureModeLimitMethod = getExposureModeMethod = setExposureModeMethod = null;
        updateExposurePriorityLimitMethod = getExposurePriorityMethod = setExposurePriorityMethod = null;
        nativeExposureReady = false;
    }

    private boolean isAutoExposureCurrentlyEnabled() { try { updateExposureModeLimit(); return ((Integer) getExposureModeMethod.invoke(null, nativePointer())) != MANUAL_EXPOSURE_MODE; } catch (Throwable ignored) { return true; } }
    private boolean setAutoExposureEnabled(boolean enabled) { try { updateExposureModeLimit(); setExposureModeMethod.invoke(null, nativePointer(), enabled ? preferredAutoExposureMode() : MANUAL_EXPOSURE_MODE); return true; } catch (Throwable ignored) { return false; } }
    private boolean setFixedFrameRateExposurePriority() { try { updateExposurePriorityLimit(); setExposurePriorityMethod.invoke(null, nativePointer(), FIXED_FRAME_RATE_EXPOSURE_PRIORITY); return true; } catch (Throwable ignored) { return false; } }
    private int exposurePercentFromDevice() { try { updateExposureLimit(); return rawExposureToPercent((Integer) getExposureMethod.invoke(null, nativePointer())); } catch (Throwable ignored) { return -1; } }
    private boolean setExposurePercentOnDevice(int percent) { try { updateExposureLimit(); setExposureMethod.invoke(null, nativePointer(), percentToRawExposure(percent)); return true; } catch (Throwable ignored) { return false; } }
    private void updateExposureLimit() throws ReflectiveOperationException { updateExposureLimitMethod.invoke(activeCamera, nativePointer()); }
    private void updateExposureModeLimit() throws ReflectiveOperationException { updateExposureModeLimitMethod.invoke(activeCamera, nativePointer()); }
    private void updateExposurePriorityLimit() throws ReflectiveOperationException { updateExposurePriorityLimitMethod.invoke(activeCamera, nativePointer()); }
    private long nativePointer() throws IllegalAccessException { return nativePointerField.getLong(activeCamera); }
    private int preferredAutoExposureMode() throws IllegalAccessException { int mode = exposureModeDefField.getInt(activeCamera); return mode == MANUAL_EXPOSURE_MODE ? AUTO_EXPOSURE_MODE : mode; }
    private int rawExposureToPercent(int raw) throws IllegalAccessException { int min = exposureMinField.getInt(activeCamera), max = exposureMaxField.getInt(activeCamera), range = Math.abs(max - min); return range <= 0 ? 0 : clamp(Math.round((raw - min) * 100.0f / range), 0, 100); }
    private int percentToRawExposure(int percent) throws IllegalAccessException { int min = exposureMinField.getInt(activeCamera), max = exposureMaxField.getInt(activeCamera); return Math.round(min + (Math.abs(max - min) * clamp(percent, 0, 100) / 100.0f)); }
    private Field field(Class<?> cls, String name) throws NoSuchFieldException { Field field = cls.getDeclaredField(name); field.setAccessible(true); return field; }
    private Method method(Class<?> cls, String name, Class<?>... parameterTypes) throws NoSuchMethodException { Method method = cls.getDeclaredMethod(name, parameterTypes); method.setAccessible(true); return method; }

    private String describeExposureRange() { try { updateExposureLimit(); return "raw min=" + exposureMinField.getInt(activeCamera) + ", max=" + exposureMaxField.getInt(activeCamera) + ", def=" + exposureDefField.getInt(activeCamera) + ", priority supported=" + exposurePrioritySupported; } catch (Throwable ignored) { return "raw range unavailable"; } }
    private String describeExposureState() { if (!nativeExposureReady || activeCamera == null) return "Exposure state unavailable."; StringBuilder b = new StringBuilder("Exposure state:"); try { updateExposureModeLimit(); b.append(" mode=").append((Integer) getExposureModeMethod.invoke(null, nativePointer())); } catch (Throwable ignored) { b.append(" mode=?"); } if (exposurePrioritySupported) { try { updateExposurePriorityLimit(); b.append(", priority=").append((Integer) getExposurePriorityMethod.invoke(null, nativePointer())); } catch (Throwable ignored) { b.append(", priority=?"); } } else b.append(", priority=unsupported"); try { updateExposureLimit(); b.append(", rawExposure=").append((Integer) getExposureMethod.invoke(null, nativePointer())); } catch (Throwable ignored) { b.append(", rawExposure=?"); } return b.toString(); }

    private void scanRawUsbDevices() { rawUsbDevicesById.clear(); try { for (UsbDevice device : usbMonitor.getDeviceList()) rememberDevice(device); if (usbManager != null) for (UsbDevice device : usbManager.getDeviceList().values()) rememberDevice(device); } catch (Throwable throwable) { statusOnly("USB device scan failed: " + describeThrowable(throwable)); } }
    private void rememberDevice(UsbDevice device) { if (device != null) rawUsbDevicesById.put(device.getDeviceId(), device); }
    private UsbDevice firstRawUsbDevice() { for (UsbDevice device : rawUsbDevicesById.values()) return device; return null; }
    private StreamMode chooseDefaultMode() { StreamMode yuyv = bestMode(UVCCamera.FRAME_FORMAT_YUYV); return yuyv != null ? yuyv : bestMode(UVCCamera.FRAME_FORMAT_MJPEG); }
    private StreamMode bestMode(int format) { StreamMode best = null; for (StreamMode mode : availableStreamModes) if (mode.frameFormat == format && (best == null || rank(mode) > rank(best))) best = mode; return best; }
    private long rank(StreamMode mode) { return (long) mode.width * mode.height * 10000L + mode.fps; }
    private StreamMode equivalentModeOrDefault(StreamMode requested) { if (requested != null) for (StreamMode mode : availableStreamModes) if (sameMode(mode, requested)) return mode; return chooseDefaultMode(); }
    private void addUniqueMode(List<StreamMode> modes, StreamMode candidate) { for (StreamMode mode : modes) if (sameMode(mode, candidate)) return; modes.add(candidate); }
    private boolean sameMode(StreamMode a, StreamMode b) { return a != null && b != null && a.frameFormat == b.frameFormat && a.width == b.width && a.height == b.height && a.fps == b.fps; }
    private void clearControlFlags() { brightnessSupported = contrastSupported = gainSupported = exposureSupported = autoExposureSupported = exposurePrioritySupported = false; }

    private Capabilities buildCapabilities(String statusText) { return new Capabilities(activeCamera != null, previewRunning, availableStreamModes, selectedStreamMode, activeStreamMode, brightnessSupported, contrastSupported, gainSupported, exposureSupported, autoExposureSupported, exposurePercent, autoExposureEnabled, nativeExposureReady ? describeExposureRange() : "native exposure access unavailable", statusText); }
    private void publish(String statusText) { final Capabilities capabilities = buildCapabilities(statusText); statusOnly(statusText); mainHandler.post(new Runnable() { @Override public void run() { if (listener != null) listener.onCameraCapabilitiesChanged(capabilities); } }); }
    private void statusOnly(final String statusText) { record(statusText); mainHandler.post(new Runnable() { @Override public void run() { if (listener != null) listener.onCameraStatusChanged(statusText); } }); }
    private void record(String message) { if (message == null || message.trim().isEmpty()) return; debugLog.append(String.format(Locale.US, "[%tF %<tT] %s\n", new Date(), message.trim())); if (debugLog.length() > MAX_LOG_CHARS) debugLog.delete(0, debugLog.length() - MAX_LOG_CHARS); }
    private String buildDebugExportText() { return "Android Polaris UVC debug log\nGenerated: " + String.format(Locale.US, "%tF %<tT", new Date()) + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n\nCurrent UVC state:\n" + describeConnectedDevices() + "\n\nLog:\n" + (debugLog.length() == 0 ? "No UVC log lines recorded." : debugLog.toString().trim()); }
    private String describeStreamModes() { if (availableStreamModes.isEmpty()) return "No stream modes reported."; StringBuilder b = new StringBuilder("Stream modes:"); for (StreamMode mode : availableStreamModes) b.append("\n  • ").append(mode.fullLabel()); return b.toString(); }
    private String describeDeviceBrief(UsbDevice device) { return String.format(Locale.US, "VID %04x / PID %04x", device.getVendorId(), device.getProductId()); }
    private String describeDeviceLong(UsbDevice device) { StringBuilder b = new StringBuilder(); b.append(describeDeviceBrief(device)).append(", class=").append(device.getDeviceClass()).append('/').append(device.getDeviceSubclass()).append('/').append(device.getDeviceProtocol()).append(", interfaces=").append(device.getInterfaceCount()).append(", name=").append(device.getDeviceName()); for (int i = 0; i < device.getInterfaceCount(); i++) { UsbInterface usbInterface = device.getInterface(i); if (usbInterface == null) continue; b.append("; if").append(i).append('=').append(usbInterface.getInterfaceClass()).append('/').append(usbInterface.getInterfaceSubclass()).append('/').append(usbInterface.getInterfaceProtocol()); if (usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) b.append(" USB_VIDEO"); } return b.toString(); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String describeThrowable(Throwable throwable) { String message = throwable.getMessage(); return throwable.getClass().getSimpleName() + ": " + (message == null || message.trim().isEmpty() ? "no detail message" : message); }
}
