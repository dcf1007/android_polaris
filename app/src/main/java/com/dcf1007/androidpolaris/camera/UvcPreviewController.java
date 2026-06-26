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

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.serenegiant.usb.USBMonitor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int CAMERA_EFFECT_FILTER_CLASSIFY_ID = 1;
    private static final long SURFACE_RECHECK_DELAY_MS = 250L;
    private static final String SETTINGS = "android_polaris_uvc_camera_controls";
    private static final float DEFAULT_PREVIEW_SOURCE_ASPECT = 4.0f / 3.0f;

    public enum PreviewFitMode { COVER, CONTAIN, STRETCH }
    public interface Listener { void onUvcStatusChanged(String statusText); }

    private final Context context;
    private final FrameLayout previewContainer;
    private final AspectRatioTextureView previewView;
    private final Listener listener;
    private final MultiCameraClient uvcClient;
    private final Map<Integer, UsbDevice> detectedUvcDevicesById = new LinkedHashMap<>();
    private final Map<Integer, MultiCameraClient.Camera> uvcCamerasByDeviceId = new LinkedHashMap<>();

    private boolean isUsbMonitorRegistered;
    private boolean isPreviewSurfaceAvailable;
    private boolean isOpenAttemptScheduled;
    private UsbDevice pendingOpenDevice;
    private MultiCameraClient.Camera activeUvcCamera;
    private PreviewFitMode previewFitMode = PreviewFitMode.COVER;

    private LinearLayout controlsPanel;
    private TextView controlStatusTextView;
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
    private Object blackWhiteEffect;
    private UvcExposureBridge exposureBridge;
    private boolean exposureSupported;
    private boolean autoExposureSupported;

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;
        this.previewView = new AspectRatioTextureView(context);
        configurePreviewSurfaceCallbacks();
        previewContainer.removeAllViews();
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        previewContainer.setVisibility(View.VISIBLE);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) applyPreviewFitMode();
            }
        });
        this.uvcClient = new MultiCameraClient(context, createDeviceConnectionCallback());
        buildControlsPanel();
        loadControlSettings();
        scheduleSurfaceAvailabilityRecheck("initial preview view attach");
    }

    public boolean register() {
        if (isUsbMonitorRegistered) return true;
        try {
            uvcClient.register();
            isUsbMonitorRegistered = true;
            refreshDetectedDeviceCacheFromUsbHost();
            notifyStatus("UVC monitor registered. Tap Open USB UVC camera if permission is not requested automatically.");
            return true;
        } catch (Throwable throwable) {
            isUsbMonitorRegistered = false;
            notifyStatus("UVC monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    public void unregister() {
        closeActiveCamera();
        if (!isUsbMonitorRegistered) return;
        try { uvcClient.unRegister(); }
        catch (Throwable throwable) { notifyStatus("UVC monitor unregister warning: " + describeThrowable(throwable)); }
        finally { isUsbMonitorRegistered = false; }
    }

    public void destroy() {
        closeActiveCamera();
        removeControlsPanel();
        uvcCamerasByDeviceId.clear();
        detectedUvcDevicesById.clear();
        try { uvcClient.destroy(); }
        catch (Throwable throwable) { notifyStatus("UVC destroy warning: " + describeThrowable(throwable)); }
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
            if (!uvcClient.requestPermission(firstDevice)) {
                notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
            }
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
        builder.append("Preview source: USB OTG UVC backend only. Exposure is accessed through the lower-level libuvc UVCCamera bridge when the device advertises AE_ABS support.");
        return builder.toString().trim();
    }

    public void closeActiveCamera() {
        if (activeUvcCamera != null) {
            removeBlackWhiteEffect();
            try { activeUvcCamera.closeCamera(); }
            catch (Throwable throwable) { notifyStatus("UVC close warning: " + describeThrowable(throwable)); }
            activeUvcCamera = null;
        }
        setControlAvailability(null);
        isOpenAttemptScheduled = false;
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    private IDeviceConnectCallBack createDeviceConnectionCallback() {
        return new IDeviceConnectCallBack() {
            @Override public void onAttachDev(UsbDevice device) {
                if (!isUvcVideoDevice(device)) return;
                rememberDetectedDevice(device);
                notifyStatus("UVC attached: " + describeDeviceBrief(device) + ". Tap Open USB UVC camera to request permission.");
            }
            @Override public void onDetachDec(UsbDevice device) {
                if (device == null) return;
                detectedUvcDevicesById.remove(device.getDeviceId());
                MultiCameraClient.Camera removed = uvcCamerasByDeviceId.remove(device.getDeviceId());
                if (removed != null) try { removed.closeCamera(); } catch (Throwable ignored) { }
                if (pendingOpenDevice != null && pendingOpenDevice.getDeviceId() == device.getDeviceId()) pendingOpenDevice = null;
                if (activeUvcCamera == removed) { activeUvcCamera = null; setControlAvailability(null); }
                notifyStatus("UVC detached: " + device.getDeviceName());
            }
            @Override public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                if (!isUvcVideoDevice(device) || controlBlock == null) return;
                try {
                    rememberDetectedDevice(device);
                    pendingOpenDevice = device;
                    MultiCameraClient.Camera camera = getOrCreateUvcCamera(device);
                    camera.setUsbControlBlock(controlBlock);
                    camera.setCameraStateCallBack(createCameraStateCallback());
                    activeUvcCamera = camera;
                    openCameraWhenPreviewSurfaceIsReady(camera, device);
                } catch (Throwable throwable) {
                    notifyStatus("UVC connect handling failed: " + describeThrowable(throwable));
                }
            }
            @Override public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                closeActiveCamera();
                notifyStatus("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onCancelDev(UsbDevice device) {
                pendingOpenDevice = null;
                notifyStatus("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
            }
        };
    }

    private ICameraStateCallBack createCameraStateCallback() {
        return new ICameraStateCallBack() {
            @Override public void onCameraState(MultiCameraClient.Camera camera, ICameraStateCallBack.State state, String message) {
                if (state == ICameraStateCallBack.State.OPENED) {
                    activeUvcCamera = camera;
                    applyPreviewFitMode();
                    setControlAvailability(camera);
                    notifyStatus("UVC preview opened. Exposure controls are queried from lower-level UVCCamera support flags.");
                } else if (state == ICameraStateCallBack.State.CLOSED) {
                    setControlAvailability(null);
                    notifyStatus("UVC preview closed.");
                } else if (state == ICameraStateCallBack.State.ERROR) {
                    notifyStatus("UVC preview error: " + (message == null ? "unknown error" : message));
                }
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
                if (activeUvcCamera != null && pendingOpenDevice != null) openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
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
        if (previewFitMode != PreviewFitMode.STRETCH) {
            boolean containerWide = cw / (float) ch > DEFAULT_PREVIEW_SOURCE_ASPECT;
            if (previewFitMode == PreviewFitMode.CONTAIN) {
                if (containerWide) { th = ch; tw = Math.round(th * DEFAULT_PREVIEW_SOURCE_ASPECT); }
                else { tw = cw; th = Math.round(tw / DEFAULT_PREVIEW_SOURCE_ASPECT); }
            } else {
                if (containerWide) { tw = cw; th = Math.round(tw / DEFAULT_PREVIEW_SOURCE_ASPECT); }
                else { th = ch; tw = Math.round(th * DEFAULT_PREVIEW_SOURCE_ASPECT); }
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

    private void openCameraWhenPreviewSurfaceIsReady(MultiCameraClient.Camera camera, UsbDevice device) {
        isPreviewSurfaceAvailable = previewView.isAvailable();
        if (!isPreviewSurfaceAvailable) {
            notifyStatus("UVC permission granted for " + describeDeviceBrief(device) + ", waiting for preview surface. Current preview view size: " + previewView.getWidth() + "×" + previewView.getHeight() + ".");
            scheduleOpenAttemptAfterSurfaceRecheck(camera, device);
            return;
        }
        try {
            camera.openCamera(previewView, createPreviewRequest());
            applyPreviewFitMode();
            notifyStatus("Opening UVC preview for " + describeDeviceBrief(device) + "…");
        } catch (Throwable throwable) {
            notifyStatus("Failed to open UVC preview: " + describeThrowable(throwable));
        }
    }

    private void scheduleSurfaceAvailabilityRecheck(String reason) {
        previewView.postDelayed(new Runnable() {
            @Override public void run() {
                isPreviewSurfaceAvailable = previewView.isAvailable();
                applyPreviewFitMode();
                if (isPreviewSurfaceAvailable) {
                    notifyStatus("Preview surface ready after " + reason + ".");
                    if (activeUvcCamera != null && pendingOpenDevice != null) openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
                }
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private void scheduleOpenAttemptAfterSurfaceRecheck(MultiCameraClient.Camera camera, UsbDevice device) {
        if (isOpenAttemptScheduled) return;
        isOpenAttemptScheduled = true;
        previewView.postDelayed(new Runnable() {
            @Override public void run() {
                isOpenAttemptScheduled = false;
                isPreviewSurfaceAvailable = previewView.isAvailable();
                applyPreviewFitMode();
                if (isPreviewSurfaceAvailable) openCameraWhenPreviewSurfaceIsReady(camera, device);
                else notifyStatus("Preview surface still unavailable. Preview view size: " + previewView.getWidth() + "×" + previewView.getHeight() + ".");
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private CameraRequest createPreviewRequest() {
        return new CameraRequest.Builder().setContinuousAFModel(true).setContinuousAutoModel(true).create();
    }

    private MultiCameraClient.Camera getOrCreateUvcCamera(UsbDevice device) {
        MultiCameraClient.Camera existing = uvcCamerasByDeviceId.get(device.getDeviceId());
        if (existing != null) return existing;
        MultiCameraClient.Camera created = new MultiCameraClient.Camera(context, device);
        uvcCamerasByDeviceId.put(device.getDeviceId(), created);
        return created;
    }

    private void refreshDetectedDeviceCacheFromUsbHost() {
        for (UsbDevice device : getUvcDevicesFromClientSafely()) rememberDetectedDevice(device);
    }

    private List<UsbDevice> getUvcDevicesFromClientSafely() {
        List<UsbDevice> out = new ArrayList<>();
        try {
            List<UsbDevice> all = uvcClient.getDeviceList(null);
            if (all != null) for (UsbDevice device : all) if (isUvcVideoDevice(device)) out.add(device);
        } catch (Throwable throwable) { notifyStatus("USB device scan failed: " + describeThrowable(throwable)); }
        return out;
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
        autoExposureCheckBox = new CheckBox(context); autoExposureCheckBox.setText("Auto exposure"); autoExposureCheckBox.setTextColor(Color.rgb(243,245,247)); autoExposureCheckBox.setTextSize(12);
        blackWhiteCheckBox = new CheckBox(context); blackWhiteCheckBox.setText("B&W"); blackWhiteCheckBox.setTextColor(Color.rgb(243,245,247)); blackWhiteCheckBox.setTextSize(12);
        body.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(-1, -2));
        body.addView(blackWhiteCheckBox, new LinearLayout.LayoutParams(-1, -2));
        TextView fpsNote = text("FPS is still hidden: it requires choosing a supported preview size/FPS tuple before stream open, not a live UVC control.", 11, false);
        fpsNote.setTextColor(Color.rgb(255,212,121)); body.addView(fpsNote, new LinearLayout.LayoutParams(-1, -2));
        controlStatusTextView = text("Open UVC camera to query exposure support.", 11, false); body.addView(controlStatusTextView, new LinearLayout.LayoutParams(-1, -2));
        controlsPanel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(190)));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); pp.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(controlsPanel, pp);
        wireControls();
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
        setControlAvailability(null);
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
    private void updateLabels() { brightnessValueTextView.setText(String.format(Locale.US, "%+d display", (brightnessValue - 50) * 2)); contrastValueTextView.setText(String.format(Locale.US, "%.2fx", Math.max(0.05f, contrastValue / 50f))); gainValueTextView.setText(String.format(Locale.US, "%d raw", gainValue)); exposureValueTextView.setText(String.format(Locale.US, "%d%% %s", exposureValue, autoExposure ? "auto" : "manual")); }

    private void setControlAvailability(MultiCameraClient.Camera camera) {
        exposureBridge = camera == null ? null : UvcExposureBridge.fromAusbcCamera(camera);
        exposureSupported = exposureBridge != null && exposureBridge.supportsAbsoluteExposure();
        autoExposureSupported = exposureBridge != null && exposureBridge.supportsAutoExposureMode();
        boolean gain = camera != null && hasMethod(camera, "setGain", int.class);
        if (controlsPanel != null) {
            gainSeekBar.setEnabled(gain);
            autoExposureCheckBox.setEnabled(autoExposureSupported);
            if (exposureBridge != null) queryExposureFromDevice();
            updateExposureWidgetState();
            String text = camera == null ? "Open UVC camera to query exposure support." : String.format(Locale.US,
                    "AUSBC methods: brightness=%s, contrast=%s, gain=%s, B&W effect=%s. Exposure bridge: abs=%s, auto=%s, %s.",
                    hasMethod(camera, "setBrightness", int.class), hasMethod(camera, "setContrast", int.class), gain,
                    canUseBlackWhiteEffect(camera), exposureSupported, autoExposureSupported,
                    exposureBridge == null ? "no UVCCamera handle" : exposureBridge.describeExposureRange());
            controlStatusTextView.setText(text);
        }
    }

    private void queryExposureFromDevice() {
        loadingControls = true;
        if (autoExposureSupported) {
            autoExposure = exposureBridge.isAutoExposureEnabled();
            autoExposureCheckBox.setChecked(autoExposure);
        }
        if (exposureSupported) {
            int currentExposure = exposureBridge.getExposurePercent();
            if (currentExposure >= 0) {
                exposureValue = currentExposure;
                exposureSeekBar.setProgress(currentExposure);
            }
        }
        loadingControls = false;
        updateLabels();
    }

    private void updateExposureWidgetState() {
        if (exposureSeekBar != null) exposureSeekBar.setEnabled(exposureSupported && !autoExposure);
    }

    private void applyControls(String reason) {
        applyDisplayFilter();
        if (activeUvcCamera == null) { setStatus("Controls applied (" + reason + "). Display fallback only until camera opens."); return; }
        int accepted = 0;
        if (callInt(activeUvcCamera, "setBrightness", brightnessValue)) accepted++;
        if (callInt(activeUvcCamera, "setContrast", contrastValue)) accepted++;
        if (callInt(activeUvcCamera, "setGain", gainValue)) accepted++;
        boolean aeOk = false, exposureOk = false;
        if (exposureBridge != null) {
            if (autoExposureSupported) aeOk = exposureBridge.setAutoExposureEnabled(autoExposure);
            if (exposureSupported && !autoExposure) exposureOk = exposureBridge.setExposurePercent(exposureValue);
        }
        boolean bwOk = blackWhite ? applyBlackWhiteEffect() : removeBlackWhiteEffect();
        setStatus("Controls applied (" + reason + "). AUSBC setters=" + accepted + "; AE=" + aeOk + "; exposure=" + exposureOk + "; B&W effect=" + bwOk + ".");
    }

    private void applyDisplayFilter() {
        float c = Math.max(0.05f, contrastValue / 50f), b = (brightnessValue - 50) * 2.55f;
        ColorMatrix cm = new ColorMatrix(); if (blackWhite) cm.setSaturation(0);
        cm.postConcat(new ColorMatrix(new float[]{c,0,0,0,b, 0,c,0,0,b, 0,0,c,0,b, 0,0,0,1,0}));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColorFilter(new ColorMatrixColorFilter(cm)); previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint); previewView.invalidate();
    }

    private boolean canUseBlackWhiteEffect(Object camera) { return classAvailable("com.jiangdg.ausbc.render.effect.EffectBlackWhite") && (hasAnyMethod(camera, "addRenderEffect") || hasAnyMethod(camera, "updateRenderEffect")); }
    private boolean applyBlackWhiteEffect() { if (activeUvcCamera == null) return false; if (blackWhiteEffect == null) blackWhiteEffect = newBlackWhiteEffect(); if (blackWhiteEffect == null) return false; return callOne(activeUvcCamera, "addRenderEffect", blackWhiteEffect) || callTwo(activeUvcCamera, "updateRenderEffect", CAMERA_EFFECT_FILTER_CLASSIFY_ID, blackWhiteEffect); }
    private boolean removeBlackWhiteEffect() { if (activeUvcCamera == null || blackWhiteEffect == null) { blackWhiteEffect = null; return false; } boolean ok = callOne(activeUvcCamera, "removeRenderEffect", blackWhiteEffect); blackWhiteEffect = null; return ok; }
    private Object newBlackWhiteEffect() { try { Class<?> c = Class.forName("com.jiangdg.ausbc.render.effect.EffectBlackWhite"); for (Constructor<?> x : c.getConstructors()) if (x.getParameterTypes().length == 1) return x.newInstance(context); } catch (Throwable ignored) { } return null; }

    private boolean callInt(Object target, String name, int value) { try { target.getClass().getMethod(name, int.class).invoke(target, value); return true; } catch (Throwable ignored) { return false; } }
    private boolean callOne(Object target, String name, Object arg) { try { for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) { m.invoke(target, arg); return true; } } catch (Throwable ignored) { } return false; }
    private boolean callTwo(Object target, String name, int a, Object b) { try { for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 2 && m.getParameterTypes()[1].isAssignableFrom(b.getClass())) { m.invoke(target, a, b); return true; } } catch (Throwable ignored) { } return false; }
    private boolean hasMethod(Object target, String name, Class<?>... types) { try { target.getClass().getMethod(name, types); return true; } catch (Throwable ignored) { return false; } }
    private boolean hasAnyMethod(Object target, String name) { for (Method m : target.getClass().getMethods()) if (m.getName().equals(name)) return true; return false; }
    private boolean classAvailable(String name) { try { Class.forName(name); return true; } catch (Throwable ignored) { return false; } }

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
    private void notifyStatus(String s) { if (listener != null) listener.onUvcStatusChanged(s); }
    private int dp(int v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
