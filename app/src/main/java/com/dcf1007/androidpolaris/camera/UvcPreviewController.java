package com.dcf1007.androidpolaris.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jiangdg.usb.USBMonitor;
import com.jiangdg.utils.Size;
import com.jiangdg.uvc.UVCCamera;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Direct libuvc preview and camera-control backend. No AUSBC wrapper is used. */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final long SURFACE_RECHECK_DELAY_MS = 250L;
    private static final String SETTINGS = "android_polaris_uvc_camera_controls";
    private static final float DEFAULT_PREVIEW_SOURCE_ASPECT = 4.0f / 3.0f;

    public enum PreviewFitMode { COVER, CONTAIN, STRETCH }
    public interface Listener { void onUvcStatusChanged(String statusText); }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout previewContainer;
    private final TextureView previewView;
    private final Listener listener;
    private final USBMonitor usbMonitor;
    private final Map<Integer, UsbDevice> detectedUvcDevicesById = new LinkedHashMap<>();

    private boolean isUsbMonitorRegistered;
    private boolean isPreviewSurfaceAvailable;
    private boolean isOpenAttemptScheduled;
    private UsbDevice pendingOpenDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private UVCCamera activeCamera;
    private PreviewFitMode previewFitMode = PreviewFitMode.COVER;
    private int activePreviewWidth = 0;
    private int activePreviewHeight = 0;
    private int activePreviewFormat = UVCCamera.FRAME_FORMAT_MJPEG;
    private String supportedStreamModesText = "Stream modes: open the UVC camera to query formats.";

    private LinearLayout controlsPanel;
    private TextView controlStatusTextView;
    private TextView streamModesTextView;
    private TextView brightnessValueTextView;
    private TextView contrastValueTextView;
    private TextView gainValueTextView;
    private TextView exposureValueTextView;
    private SeekBar brightnessSeekBar;
    private SeekBar contrastSeekBar;
    private SeekBar gainSeekBar;
    private SeekBar exposureSeekBar;
    private CheckBox autoExposureCheckBox;
    private CheckBox blackWhiteCheckBox;
    private boolean controlsCollapsed;
    private boolean loadingControls;

    private int brightnessValue = 50;
    private int contrastValue = 50;
    private int gainValue = 0;
    private int exposureValue = 50;
    private boolean autoExposure = true;
    private boolean blackWhite;
    private boolean brightnessSupported;
    private boolean contrastSupported;
    private boolean gainSupported;
    private boolean exposureSupported;
    private boolean autoExposureSupported;
    private UvcExposureBridge exposureBridge;

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;
        this.previewView = new TextureView(context);
        configurePreviewSurfaceCallbacks();
        previewContainer.removeAllViews();
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        previewContainer.setVisibility(View.VISIBLE);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) applyPreviewFitMode();
            }
        });
        usbMonitor = new USBMonitor(context, createUsbListener());
        buildControlsPanel();
        loadControlSettings();
        scheduleSurfaceAvailabilityRecheck("initial preview view attach");
    }

    public boolean register() {
        if (isUsbMonitorRegistered) return true;
        try {
            usbMonitor.register();
            isUsbMonitorRegistered = true;
            refreshDetectedDeviceCacheFromUsbHost();
            notifyStatus("Direct libuvc monitor registered. Tap Open USB UVC camera if permission is not requested automatically.");
            return true;
        } catch (Throwable throwable) {
            isUsbMonitorRegistered = false;
            notifyStatus("Direct libuvc monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    public void unregister() {
        closeActiveCamera();
        if (!isUsbMonitorRegistered) return;
        try { usbMonitor.unregister(); }
        catch (Throwable throwable) { notifyStatus("USB monitor unregister warning: " + describeThrowable(throwable)); }
        finally { isUsbMonitorRegistered = false; }
    }

    public void destroy() {
        closeActiveCamera();
        removeControlsPanel();
        detectedUvcDevicesById.clear();
        try { usbMonitor.destroy(); }
        catch (Throwable throwable) { notifyStatus("USB monitor destroy warning: " + describeThrowable(throwable)); }
        previewContainer.removeAllViews();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) return;
        refreshDetectedDeviceCacheFromUsbHost();
        UsbDevice firstDevice = getFirstDetectedUvcDevice();
        if (firstDevice == null) {
            notifyStatus("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }
        pendingOpenDevice = firstDevice;
        notifyStatus("Requesting USB permission for " + describeDeviceBrief(firstDevice) + "…");
        try {
            boolean failed = usbMonitor.requestPermission(firstDevice);
            if (failed) notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
        } catch (Throwable throwable) {
            notifyStatus("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public String describeConnectedUvcDevices() {
        refreshDetectedDeviceCacheFromUsbHost();
        if (detectedUvcDevicesById.isEmpty()) return "No raw USB UVC devices detected.";
        StringBuilder builder = new StringBuilder();
        builder.append(detectedUvcDevicesById.size()).append(" raw UVC device(s) detected:\n");
        for (UsbDevice device : detectedUvcDevicesById.values()) builder.append(describeDeviceLong(device)).append('\n');
        builder.append("Preview source: direct libuvc/UVCCamera. AUSBC wrapper is not used. ");
        if (activeCamera != null) {
            builder.append("Active preview: ").append(activePreviewWidth).append('×').append(activePreviewHeight)
                    .append(activePreviewFormat == UVCCamera.FRAME_FORMAT_MJPEG ? " MJPEG" : " YUYV/uncompressed").append(". ");
            builder.append("Exposure abs=").append(exposureSupported).append(", auto=").append(autoExposureSupported).append(".\n");
            builder.append(supportedStreamModesText);
        }
        return builder.toString().trim();
    }

    public void closeActiveCamera() {
        try {
            if (activeCamera != null) activeCamera.destroy();
        } catch (Throwable throwable) {
            notifyStatus("Direct libuvc close warning: " + describeThrowable(throwable));
        } finally {
            activeCamera = null;
            activeControlBlock = null;
            activePreviewWidth = 0;
            activePreviewHeight = 0;
            supportedStreamModesText = "Stream modes: open the UVC camera to query formats.";
            exposureBridge = null;
            setControlAvailability(false);
            isOpenAttemptScheduled = false;
        }
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    private USBMonitor.OnDeviceConnectListener createUsbListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) {
                if (!isUvcVideoDevice(device)) return;
                rememberDetectedDevice(device);
                notifyStatus("UVC attached: " + describeDeviceBrief(device) + ". Tap Open USB UVC camera to request permission.");
            }
            @Override public void onDetach(UsbDevice device) {
                if (device == null) return;
                detectedUvcDevicesById.remove(device.getDeviceId());
                if (pendingOpenDevice != null && pendingOpenDevice.getDeviceId() == device.getDeviceId()) pendingOpenDevice = null;
                closeActiveCamera();
                notifyStatus("UVC detached: " + device.getDeviceName());
            }
            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                if (!isUvcVideoDevice(device) || ctrlBlock == null) return;
                rememberDetectedDevice(device);
                pendingOpenDevice = device;
                activeControlBlock = ctrlBlock;
                openCameraWhenPreviewSurfaceIsReady(device, ctrlBlock);
            }
            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                closeActiveCamera();
                notifyStatus("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onCancel(UsbDevice device) {
                pendingOpenDevice = null;
                notifyStatus("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
            }
        };
    }

    private void configurePreviewSurfaceCallbacks() {
        isPreviewSurfaceAvailable = previewView.isAvailable();
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                isPreviewSurfaceAvailable = true;
                applyPreviewFitMode();
                notifyStatus("Preview surface ready: " + width + "×" + height + ".");
                if (pendingOpenDevice != null && activeControlBlock != null) openCameraWhenPreviewSurfaceIsReady(pendingOpenDevice, activeControlBlock);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { applyPreviewFitMode(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) { isPreviewSurfaceAvailable = false; closeActiveCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });
    }

    private void applyPreviewFitMode() { previewView.post(new Runnable() { @Override public void run() { applyPreviewFitModeNow(); } }); }

    private void applyPreviewFitModeNow() {
        int cw = previewContainer.getWidth(), ch = previewContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        int tw = cw, th = ch;
        float sourceAspect = activePreviewWidth > 0 && activePreviewHeight > 0 ? activePreviewWidth / (float) activePreviewHeight : DEFAULT_PREVIEW_SOURCE_ASPECT;
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
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        tw = Math.max(1, tw); th = Math.max(1, th);
        if (p.width != tw || p.height != th || p.gravity != Gravity.CENTER) {
            p.width = tw; p.height = th; p.gravity = Gravity.CENTER; previewView.setLayoutParams(p);
        }
        previewView.setTranslationX(0); previewView.setTranslationY(0); previewView.setScaleX(1); previewView.setScaleY(1);
        previewView.setPivotX(tw / 2f); previewView.setPivotY(th / 2f);
        previewContainer.invalidate();
    }

    private void openCameraWhenPreviewSurfaceIsReady(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                isPreviewSurfaceAvailable = previewView.isAvailable() && previewView.getSurfaceTexture() != null;
                if (!isPreviewSurfaceAvailable) {
                    notifyStatus("UVC permission granted for " + describeDeviceBrief(device) + ", waiting for preview surface.");
                    scheduleOpenAttemptAfterSurfaceRecheck(device, ctrlBlock);
                    return;
                }
                openDirectCamera(device, ctrlBlock);
            }
        });
    }

    private void openDirectCamera(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        closeActiveCamera();
        try {
            UVCCamera camera = new UVCCamera();
            camera.open(ctrlBlock);
            supportedStreamModesText = describeSupportedStreamModes(camera);
            if (streamModesTextView != null) streamModesTextView.setText(supportedStreamModesText);
            SizeSelection selection = choosePreviewSize(camera, UVCCamera.FRAME_FORMAT_MJPEG);
            if (selection == null) selection = choosePreviewSize(camera, UVCCamera.FRAME_FORMAT_YUYV);
            if (selection == null) throw new IllegalStateException("No supported UVC preview size reported by camera");
            camera.setPreviewSize(selection.width, selection.height, 1, 31, selection.format, UVCCamera.DEFAULT_BANDWIDTH);
            camera.setPreviewTexture(previewView.getSurfaceTexture());
            camera.startPreview();
            camera.updateCameraParams();
            activeCamera = camera;
            activeControlBlock = ctrlBlock;
            activePreviewWidth = selection.width;
            activePreviewHeight = selection.height;
            activePreviewFormat = selection.format;
            exposureBridge = UvcExposureBridge.fromDirectCamera(camera);
            setControlAvailability(true);
            applyControls("camera opened");
            applyPreviewFitMode();
            notifyStatus("Direct libuvc preview opened for " + describeDeviceBrief(device) + " at "
                    + selection.width + "×" + selection.height
                    + (selection.format == UVCCamera.FRAME_FORMAT_MJPEG ? " MJPEG." : " YUYV/uncompressed."));
        } catch (Throwable throwable) {
            activeCamera = null;
            exposureBridge = null;
            setControlAvailability(false);
            notifyStatus("Failed to open direct libuvc preview: " + describeThrowable(throwable));
        }
    }

    private String describeSupportedStreamModes(UVCCamera camera) {
        StringBuilder builder = new StringBuilder("Supported stream modes:");
        appendStreamModeList(builder, camera, UVCCamera.FRAME_FORMAT_YUYV, "YUYV / uncompressed");
        appendStreamModeList(builder, camera, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG / compressed");
        return builder.toString();
    }

    private void appendStreamModeList(StringBuilder builder, UVCCamera camera, int format, String label) {
        List<Size> sizes;
        try { sizes = camera.getSupportedSizeList(format); }
        catch (Throwable ignored) { sizes = null; }
        builder.append('\n').append(label).append(':');
        if (sizes == null || sizes.isEmpty()) {
            builder.append(" none reported");
            return;
        }
        int shown = 0;
        for (Size size : sizes) {
            if (shown >= 8) {
                builder.append(" …");
                break;
            }
            builder.append('\n').append("  • ").append(size.width).append('×').append(size.height);
            String fps = fpsSummary(size);
            if (!fps.isEmpty()) builder.append(" @ ").append(fps);
            shown++;
        }
    }

    private String fpsSummary(Size size) {
        if (size == null || size.fps == null || size.fps.length == 0) return "";
        float min = Float.MAX_VALUE;
        float max = 0.0f;
        for (float fps : size.fps) {
            if (fps <= 0.0f) continue;
            min = Math.min(min, fps);
            max = Math.max(max, fps);
        }
        if (max <= 0.0f || min == Float.MAX_VALUE) return "";
        if (Math.abs(max - min) < 0.1f) return String.format(Locale.US, "%.0f fps", max);
        return String.format(Locale.US, "%.0f–%.0f fps", min, max);
    }

    private SizeSelection choosePreviewSize(UVCCamera camera, int format) {
        List<Size> sizes;
        try { sizes = camera.getSupportedSizeList(format); }
        catch (Throwable ignored) { return null; }
        if (sizes == null || sizes.isEmpty()) return null;
        Size best = null;
        for (Size size : sizes) {
            if (size.width == 640 && size.height == 480) { best = size; break; }
            if (best == null) best = size;
        }
        return best == null ? null : new SizeSelection(best.width, best.height, format);
    }

    private void scheduleSurfaceAvailabilityRecheck(String reason) {
        previewView.postDelayed(new Runnable() {
            @Override public void run() {
                isPreviewSurfaceAvailable = previewView.isAvailable();
                applyPreviewFitMode();
                if (isPreviewSurfaceAvailable) notifyStatus("Preview surface ready after " + reason + ".");
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private void scheduleOpenAttemptAfterSurfaceRecheck(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (isOpenAttemptScheduled) return;
        isOpenAttemptScheduled = true;
        previewView.postDelayed(new Runnable() {
            @Override public void run() {
                isOpenAttemptScheduled = false;
                if (previewView.isAvailable() && previewView.getSurfaceTexture() != null) openDirectCamera(device, ctrlBlock);
                else notifyStatus("Preview surface still unavailable. Preview view size: " + previewView.getWidth() + "×" + previewView.getHeight() + ".");
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private void refreshDetectedDeviceCacheFromUsbHost() {
        try {
            for (UsbDevice device : usbMonitor.getDeviceList()) if (isUvcVideoDevice(device)) rememberDetectedDevice(device);
        } catch (Throwable throwable) { notifyStatus("USB device scan failed: " + describeThrowable(throwable)); }
    }

    private void buildControlsPanel() {
        if (!(context instanceof Activity)) return;
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        if (root == null) return;
        controlsPanel = new LinearLayout(context);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        controlsPanel.setBackgroundColor(Color.argb(235, 16, 18, 24));
        TextView title = text("Camera image controls  ▲", 13, true);
        title.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { controlsCollapsed = !controlsCollapsed; updateControlsCollapsed(); } });
        controlsPanel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        brightnessSeekBar = addSlider(body, "Brightness", 50, brightnessValueTextView = valueText());
        contrastSeekBar = addSlider(body, "Contrast", 50, contrastValueTextView = valueText());
        gainSeekBar = addSlider(body, "Gain", 0, gainValueTextView = valueText());
        exposureSeekBar = addSlider(body, "Exposure", 50, exposureValueTextView = valueText());
        autoExposureCheckBox = checkbox("Auto exposure");
        blackWhiteCheckBox = checkbox("B&W");
        body.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(-1, -2));
        body.addView(blackWhiteCheckBox, new LinearLayout.LayoutParams(-1, -2));
        streamModesTextView = text(supportedStreamModesText, 11, false);
        streamModesTextView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(streamModesTextView, new LinearLayout.LayoutParams(-1, -2));
        controlStatusTextView = text("Open UVC camera to query direct libuvc controls.", 11, false); body.addView(controlStatusTextView, new LinearLayout.LayoutParams(-1, -2));
        controlsPanel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(230)));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); pp.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(controlsPanel, pp);
        wireControls();
    }

    private CheckBox checkbox(String label) {
        CheckBox checkBox = new CheckBox(context);
        checkBox.setText(label); checkBox.setTextColor(Color.rgb(243,245,247)); checkBox.setTextSize(12);
        return checkBox;
    }

    private void wireControls() {
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && !loadingControls) { readWidgets(); updateLabels(); applyControls("control change"); saveControlSettings(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { readWidgets(); applyControls("control released"); saveControlSettings(); }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(l); contrastSeekBar.setOnSeekBarChangeListener(l); gainSeekBar.setOnSeekBarChangeListener(l); exposureSeekBar.setOnSeekBarChangeListener(l);
        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean checked) { if (!loadingControls) { readWidgets(); updateExposureWidgetState(); applyControls("auto exposure change"); saveControlSettings(); } } });
        blackWhiteCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean checked) { if (!loadingControls) { readWidgets(); applyControls("B&W change"); saveControlSettings(); } } });
        setControlAvailability(false);
    }

    private SeekBar addSlider(LinearLayout parent, String label, int initial, TextView value) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(label, 12, false), new LinearLayout.LayoutParams(0, -2, 1)); row.addView(value, new LinearLayout.LayoutParams(0, -2, 1)); parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seek = new SeekBar(context); seek.setMax(100); seek.setProgress(initial); parent.addView(seek, new LinearLayout.LayoutParams(-1, -2)); return seek;
    }

    private void loadControlSettings() {
        SharedPreferences p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        brightnessValue = clamp(p.getInt("brightness", 50), 0, 100); contrastValue = clamp(p.getInt("contrast", 50), 0, 100); gainValue = clamp(p.getInt("gain", 0), 0, 100); exposureValue = clamp(p.getInt("exposure", 50), 0, 100); autoExposure = p.getBoolean("ae", true); blackWhite = p.getBoolean("bw", false);
        if (controlsPanel != null) { loadingControls = true; brightnessSeekBar.setProgress(brightnessValue); contrastSeekBar.setProgress(contrastValue); gainSeekBar.setProgress(gainValue); exposureSeekBar.setProgress(exposureValue); autoExposureCheckBox.setChecked(autoExposure); blackWhiteCheckBox.setChecked(blackWhite); loadingControls = false; updateLabels(); updateExposureWidgetState(); }
        applyDisplayFilter();
    }

    private void saveControlSettings() {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putInt("brightness", brightnessValue).putInt("contrast", contrastValue).putInt("gain", gainValue).putInt("exposure", exposureValue).putBoolean("ae", autoExposure).putBoolean("bw", blackWhite).apply();
    }

    private void readWidgets() { brightnessValue = brightnessSeekBar.getProgress(); contrastValue = contrastSeekBar.getProgress(); gainValue = gainSeekBar.getProgress(); exposureValue = exposureSeekBar.getProgress(); autoExposure = autoExposureCheckBox.isChecked(); blackWhite = blackWhiteCheckBox.isChecked(); }
    private void updateLabels() { brightnessValueTextView.setText(String.format(Locale.US, "%d%%", brightnessValue)); contrastValueTextView.setText(String.format(Locale.US, "%d%%", contrastValue)); gainValueTextView.setText(String.format(Locale.US, "%d%%", gainValue)); exposureValueTextView.setText(String.format(Locale.US, "%d%% %s", exposureValue, autoExposure ? "auto" : "manual")); }

    private void setControlAvailability(boolean cameraOpen) {
        brightnessSupported = activeCamera != null && safeCheckSupport(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = activeCamera != null && safeCheckSupport(UVCCamera.PU_CONTRAST);
        gainSupported = activeCamera != null && safeCheckSupport(UVCCamera.PU_GAIN);
        exposureSupported = exposureBridge != null && exposureBridge.supportsAbsoluteExposure();
        autoExposureSupported = exposureBridge != null && exposureBridge.supportsAutoExposureMode();
        if (controlsPanel == null) return;
        brightnessSeekBar.setEnabled(cameraOpen && brightnessSupported);
        contrastSeekBar.setEnabled(cameraOpen && contrastSupported);
        gainSeekBar.setEnabled(cameraOpen && gainSupported);
        autoExposureCheckBox.setEnabled(cameraOpen && autoExposureSupported);
        if (cameraOpen && exposureBridge != null) queryExposureFromDevice();
        updateExposureWidgetState();
        if (streamModesTextView != null) streamModesTextView.setText(supportedStreamModesText);
        String text = !cameraOpen ? "Open UVC camera to query direct libuvc controls." : String.format(Locale.US,
                "Direct libuvc: %dx%d %s. brightness=%s, contrast=%s, gain=%s, exposure=%s, auto=%s, %s.",
                activePreviewWidth, activePreviewHeight, activePreviewFormat == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV/uncompressed",
                brightnessSupported, contrastSupported, gainSupported, exposureSupported, autoExposureSupported,
                exposureBridge == null ? "no exposure bridge" : exposureBridge.describeExposureRange());
        controlStatusTextView.setText(text);
    }

    private boolean safeCheckSupport(long flag) { try { return activeCamera.checkSupportFlag(flag); } catch (Throwable ignored) { return false; } }

    private void queryExposureFromDevice() {
        loadingControls = true;
        if (autoExposureSupported) { autoExposure = exposureBridge.isAutoExposureEnabled(); autoExposureCheckBox.setChecked(autoExposure); }
        if (exposureSupported) {
            int currentExposure = exposureBridge.getExposurePercent();
            if (currentExposure >= 0) { exposureValue = currentExposure; exposureSeekBar.setProgress(currentExposure); }
        }
        loadingControls = false;
        updateLabels();
    }

    private void updateExposureWidgetState() { if (exposureSeekBar != null) exposureSeekBar.setEnabled(activeCamera != null && exposureSupported && !autoExposure); }

    private void applyControls(String reason) {
        applyDisplayFilter();
        if (activeCamera == null) { setStatus("Controls applied (" + reason + "). Waiting for direct UVC camera."); return; }
        boolean b = false, c = false, g = false, ae = false, e = false;
        try { if (brightnessSupported) { activeCamera.setBrightness(brightnessValue); b = true; } } catch (Throwable ignored) { }
        try { if (contrastSupported) { activeCamera.setContrast(contrastValue); c = true; } } catch (Throwable ignored) { }
        try { if (gainSupported) { activeCamera.setGain(gainValue); g = true; } } catch (Throwable ignored) { }
        if (exposureBridge != null) {
            if (autoExposureSupported) ae = exposureBridge.setAutoExposureEnabled(autoExposure);
            if (exposureSupported && !autoExposure) e = exposureBridge.setExposurePercent(exposureValue);
        }
        setStatus("Controls applied (" + reason + "). brightness=" + b + "; contrast=" + c + "; gain=" + g + "; AE=" + ae + "; exposure=" + e + ".");
    }

    private void applyDisplayFilter() {
        float c = Math.max(0.05f, contrastValue / 50f), b = (brightnessValue - 50) * 2.55f;
        ColorMatrix cm = new ColorMatrix(); if (blackWhite) cm.setSaturation(0);
        cm.postConcat(new ColorMatrix(new float[]{c,0,0,0,b, 0,c,0,0,b, 0,0,c,0,b, 0,0,0,1,0}));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColorFilter(new ColorMatrixColorFilter(cm)); previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint); previewView.invalidate();
    }

    private void updateControlsCollapsed() { if (controlsPanel == null) return; for (int i = 1; i < controlsPanel.getChildCount(); i++) controlsPanel.getChildAt(i).setVisibility(controlsCollapsed ? View.GONE : View.VISIBLE); ((TextView) controlsPanel.getChildAt(0)).setText(controlsCollapsed ? "Camera image controls  ▼" : "Camera image controls  ▲"); }
    private void removeControlsPanel() { if (controlsPanel == null) return; ViewGroup p = (ViewGroup) controlsPanel.getParent(); if (p != null) p.removeView(controlsPanel); controlsPanel = null; }
    private void setStatus(String s) { if (controlStatusTextView != null) controlStatusTextView.setText(s); notifyStatus(s); }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(context); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(243,245,247)); v.setPadding(0, dp(2), 0, dp(2)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private TextView valueText() { TextView v = text("—", 11, false); v.setGravity(Gravity.END); return v; }

    private void rememberDetectedDevice(UsbDevice d) { if (d != null) detectedUvcDevicesById.put(d.getDeviceId(), d); }
    private UsbDevice getFirstDetectedUvcDevice() { for (UsbDevice d : detectedUvcDevicesById.values()) return d; return null; }
    private boolean isUvcVideoDevice(UsbDevice device) { if (device == null) return false; for (int i = 0; i < device.getInterfaceCount(); i++) { UsbInterface f = device.getInterface(i); if (f != null && f.getInterfaceClass() == USB_VIDEO_CLASS) return true; } return false; }
    private String describeDeviceBrief(UsbDevice d) { return String.format(Locale.US, "VID %04x / PID %04x", d.getVendorId(), d.getProductId()); }
    private String describeDeviceLong(UsbDevice d) { return String.format(Locale.US, "VID %04x / PID %04x, interfaces=%d, name=%s", d.getVendorId(), d.getProductId(), d.getInterfaceCount(), d.getDeviceName()); }
    private String describeThrowable(Throwable t) { String m = t.getMessage(); return t.getClass().getSimpleName() + ": " + (m == null || m.trim().isEmpty() ? "no detail message" : m); }
    private void notifyStatus(final String s) { mainHandler.post(new Runnable() { @Override public void run() { if (listener != null) listener.onUvcStatusChanged(s); } }); }
    private int dp(int v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static final class SizeSelection {
        final int width, height, format;
        SizeSelection(int width, int height, int format) { this.width = width; this.height = height; this.format = format; }
    }
}
