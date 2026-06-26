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
    private static final float DEFAULT_ASPECT = 4.0f / 3.0f;

    public enum PreviewFitMode { COVER, CONTAIN, STRETCH }
    public interface Listener { void onUvcStatusChanged(String statusText); }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout previewContainer;
    private final TextureView previewView;
    private final Listener listener;
    private final USBMonitor usbMonitor;
    private final Map<Integer, UsbDevice> devicesById = new LinkedHashMap<>();

    private UsbDevice pendingDevice;
    private USBMonitor.UsbControlBlock activeBlock;
    private UVCCamera activeCamera;
    private UvcExposureBridge exposureBridge;
    private boolean registered;
    private boolean openScheduled;
    private PreviewFitMode fitMode = PreviewFitMode.COVER;
    private int previewWidth;
    private int previewHeight;
    private int previewFormat = UVCCamera.FRAME_FORMAT_MJPEG;
    private String streamModesText = "Stream modes: open the UVC camera to query formats.";

    private LinearLayout panel;
    private TextView statusText;
    private TextView streamModesTextView;
    private TextView brightnessText;
    private TextView contrastText;
    private TextView gainText;
    private TextView exposureText;
    private SeekBar brightnessSeek;
    private SeekBar contrastSeek;
    private SeekBar gainSeek;
    private SeekBar exposureSeek;
    private CheckBox autoExposureCheck;
    private CheckBox blackWhiteCheck;
    private boolean collapsed;
    private boolean loading;

    private int brightness = 50;
    private int contrast = 50;
    private int gain = 0;
    private int exposure = 50;
    private boolean autoExposure = true;
    private boolean blackWhite;
    private boolean brightnessSupported;
    private boolean contrastSupported;
    private boolean gainSupported;
    private boolean exposureSupported;
    private boolean autoExposureSupported;

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;
        this.previewView = new TextureView(context);
        configurePreviewSurface();
        previewContainer.removeAllViews();
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        previewContainer.setVisibility(View.VISIBLE);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) applyPreviewFit();
            }
        });
        usbMonitor = new USBMonitor(context, createUsbListener());
        buildControlsPanel();
        loadSettings();
        previewView.postDelayed(new Runnable() { @Override public void run() { applyPreviewFit(); } }, SURFACE_RECHECK_DELAY_MS);
    }

    public boolean register() {
        if (registered) return true;
        try {
            usbMonitor.register();
            registered = true;
            refreshDeviceCache();
            notifyStatus("Direct libuvc monitor registered. Tap Open USB UVC camera if permission is not requested automatically.");
            return true;
        } catch (Throwable t) {
            registered = false;
            notifyStatus("Direct libuvc monitor registration failed: " + describeThrowable(t));
            return false;
        }
    }

    public void unregister() {
        closeActiveCamera();
        if (!registered) return;
        try { usbMonitor.unregister(); } catch (Throwable t) { notifyStatus("USB monitor unregister warning: " + describeThrowable(t)); }
        registered = false;
    }

    public void destroy() {
        closeActiveCamera();
        removeControlsPanel();
        devicesById.clear();
        try { usbMonitor.destroy(); } catch (Throwable t) { notifyStatus("USB monitor destroy warning: " + describeThrowable(t)); }
        previewContainer.removeAllViews();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) return;
        refreshDeviceCache();
        UsbDevice device = firstDevice();
        if (device == null) {
            notifyStatus("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }
        pendingDevice = device;
        notifyStatus("Requesting USB permission for " + brief(device) + "…");
        try {
            if (usbMonitor.requestPermission(device)) notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
        } catch (Throwable t) {
            notifyStatus("USB permission request failed: " + describeThrowable(t));
        }
    }

    public String describeConnectedUvcDevices() {
        refreshDeviceCache();
        if (devicesById.isEmpty()) return "No raw USB UVC devices detected.";
        StringBuilder b = new StringBuilder();
        b.append(devicesById.size()).append(" raw UVC device(s) detected:\n");
        for (UsbDevice d : devicesById.values()) b.append(longDevice(d)).append('\n');
        b.append("Preview source: direct libuvc/UVCCamera. AUSBC wrapper is not used.");
        if (activeCamera != null) {
            b.append("\nActive preview: ").append(previewWidth).append('×').append(previewHeight).append(' ').append(formatName(previewFormat)).append('.');
            b.append("\nExposure abs=").append(exposureSupported).append(", auto=").append(autoExposureSupported).append('.');
            b.append('\n').append(streamModesText);
        }
        return b.toString().trim();
    }

    public void closeActiveCamera() {
        try { if (activeCamera != null) activeCamera.destroy(); }
        catch (Throwable t) { notifyStatus("Direct libuvc close warning: " + describeThrowable(t)); }
        activeCamera = null;
        activeBlock = null;
        previewWidth = 0;
        previewHeight = 0;
        exposureBridge = null;
        streamModesText = "Stream modes: open the UVC camera to query formats.";
        setControlAvailability(false);
        openScheduled = false;
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        this.fitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFit();
    }

    private USBMonitor.OnDeviceConnectListener createUsbListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) {
                if (isUvc(device)) { remember(device); notifyStatus("UVC attached: " + brief(device) + ". Tap Open USB UVC camera to request permission."); }
            }
            @Override public void onDetach(UsbDevice device) {
                if (device != null) devicesById.remove(device.getDeviceId());
                closeActiveCamera();
                notifyStatus("UVC detached" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock block, boolean createNew) {
                if (!isUvc(device) || block == null) return;
                remember(device);
                pendingDevice = device;
                activeBlock = block;
                openWhenSurfaceReady(device, block);
            }
            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock block) {
                closeActiveCamera();
                notifyStatus("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }
            @Override public void onCancel(UsbDevice device) {
                pendingDevice = null;
                notifyStatus("USB permission was cancelled" + (device == null ? "." : " for " + brief(device) + "."));
            }
        };
    }

    private void configurePreviewSurface() {
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                applyPreviewFit();
                notifyStatus("Preview surface ready: " + width + "×" + height + ".");
                if (pendingDevice != null && activeBlock != null) openWhenSurfaceReady(pendingDevice, activeBlock);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { applyPreviewFit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeActiveCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    private void openWhenSurfaceReady(final UsbDevice device, final USBMonitor.UsbControlBlock block) {
        mainHandler.post(new Runnable() { @Override public void run() {
            if (!previewView.isAvailable() || previewView.getSurfaceTexture() == null) {
                scheduleOpenRetry(device, block);
                return;
            }
            openDirectCamera(device, block);
        }});
    }

    private void openDirectCamera(UsbDevice device, USBMonitor.UsbControlBlock block) {
        closeActiveCamera();
        try {
            UVCCamera camera = new UVCCamera();
            camera.open(block);
            streamModesText = describeStreamModes(camera);
            if (streamModesTextView != null) streamModesTextView.setText(streamModesText);
            SizeSelection selected = choosePreviewSize(camera, UVCCamera.FRAME_FORMAT_YUYV);
            if (selected == null) selected = choosePreviewSize(camera, UVCCamera.FRAME_FORMAT_MJPEG);
            if (selected == null) throw new IllegalStateException("No supported UVC preview size reported by camera");
            camera.setPreviewSize(selected.width, selected.height, 1, 31, selected.format, UVCCamera.DEFAULT_BANDWIDTH);
            camera.setPreviewTexture(previewView.getSurfaceTexture());
            camera.startPreview();
            camera.updateCameraParams();
            activeCamera = camera;
            activeBlock = block;
            previewWidth = selected.width;
            previewHeight = selected.height;
            previewFormat = selected.format;
            exposureBridge = UvcExposureBridge.fromDirectCamera(camera);
            setControlAvailability(true);
            applyControls("camera opened");
            applyPreviewFit();
            notifyStatus("Direct libuvc preview opened for " + brief(device) + " at " + selected.width + "×" + selected.height + " " + formatName(selected.format) + ".");
        } catch (Throwable t) {
            activeCamera = null;
            exposureBridge = null;
            setControlAvailability(false);
            notifyStatus("Failed to open direct libuvc preview: " + describeThrowable(t));
        }
    }

    private SizeSelection choosePreviewSize(UVCCamera camera, int format) {
        List<Size> sizes;
        try { sizes = camera.getSupportedSizeList(format); } catch (Throwable ignored) { return null; }
        if (sizes == null || sizes.isEmpty()) return null;
        Size best = sizes.get(0);
        for (Size s : sizes) if (s.width == 640 && s.height == 480) { best = s; break; }
        return new SizeSelection(best.width, best.height, format);
    }

    private String describeStreamModes(UVCCamera camera) {
        StringBuilder b = new StringBuilder("Supported stream modes:");
        appendModes(b, camera, UVCCamera.FRAME_FORMAT_YUYV, "YUYV / uncompressed");
        appendModes(b, camera, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG / compressed");
        return b.toString();
    }

    private void appendModes(StringBuilder b, UVCCamera camera, int format, String label) {
        List<Size> sizes;
        try { sizes = camera.getSupportedSizeList(format); } catch (Throwable ignored) { sizes = null; }
        b.append('\n').append(label).append(':');
        if (sizes == null || sizes.isEmpty()) { b.append(" none reported"); return; }
        int shown = 0;
        for (Size s : sizes) {
            if (shown++ >= 8) { b.append(" …"); break; }
            b.append('\n').append("  • ").append(s.width).append('×').append(s.height);
            String fps = fpsSummary(s);
            if (!fps.isEmpty()) b.append(" @ ").append(fps);
        }
    }

    private String fpsSummary(Size s) {
        if (s == null || s.fps == null || s.fps.length == 0) return "";
        float min = Float.MAX_VALUE, max = 0.0f;
        for (float fps : s.fps) { if (fps > 0) { min = Math.min(min, fps); max = Math.max(max, fps); } }
        if (max <= 0 || min == Float.MAX_VALUE) return "";
        return Math.abs(max - min) < 0.1f ? String.format(Locale.US, "%.0f fps", max) : String.format(Locale.US, "%.0f–%.0f fps", min, max);
    }

    private void applyPreviewFit() { previewView.post(new Runnable() { @Override public void run() { applyPreviewFitNow(); } }); }

    private void applyPreviewFitNow() {
        int cw = previewContainer.getWidth(), ch = previewContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float sourceAspect = previewWidth > 0 && previewHeight > 0 ? previewWidth / (float) previewHeight : DEFAULT_ASPECT;
        int tw = cw, th = ch;
        if (fitMode != PreviewFitMode.STRETCH) {
            boolean wide = cw / (float) ch > sourceAspect;
            if (fitMode == PreviewFitMode.CONTAIN) {
                if (wide) { th = ch; tw = Math.round(th * sourceAspect); } else { tw = cw; th = Math.round(tw / sourceAspect); }
            } else {
                if (wide) { tw = cw; th = Math.round(tw / sourceAspect); } else { th = ch; tw = Math.round(th * sourceAspect); }
            }
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        tw = Math.max(1, tw); th = Math.max(1, th);
        if (p.width != tw || p.height != th || p.gravity != Gravity.CENTER) { p.width = tw; p.height = th; p.gravity = Gravity.CENTER; previewView.setLayoutParams(p); }
        previewView.setTranslationX(0); previewView.setTranslationY(0); previewView.setScaleX(1); previewView.setScaleY(1);
        previewView.setPivotX(tw / 2f); previewView.setPivotY(th / 2f);
    }

    private void scheduleOpenRetry(final UsbDevice device, final USBMonitor.UsbControlBlock block) {
        if (openScheduled) return;
        openScheduled = true;
        previewView.postDelayed(new Runnable() { @Override public void run() {
            openScheduled = false;
            if (previewView.isAvailable() && previewView.getSurfaceTexture() != null) openDirectCamera(device, block);
            else notifyStatus("Preview surface still unavailable. Preview view size: " + previewView.getWidth() + "×" + previewView.getHeight() + ".");
        }}, SURFACE_RECHECK_DELAY_MS);
    }

    private void refreshDeviceCache() {
        try { for (UsbDevice d : usbMonitor.getDeviceList()) if (isUvc(d)) remember(d); }
        catch (Throwable t) { notifyStatus("USB device scan failed: " + describeThrowable(t)); }
    }

    private void buildControlsPanel() {
        if (!(context instanceof Activity)) return;
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        if (root == null) return;
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(Color.argb(235, 16, 18, 24));
        TextView title = text("Camera image controls  ▲", 13, true);
        title.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { collapsed = !collapsed; updateCollapsed(); } });
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        brightnessSeek = addSlider(body, "Brightness", 50, brightnessText = valueText());
        contrastSeek = addSlider(body, "Contrast", 50, contrastText = valueText());
        gainSeek = addSlider(body, "Gain", 0, gainText = valueText());
        exposureSeek = addSlider(body, "Exposure", 50, exposureText = valueText());
        autoExposureCheck = checkbox("Auto exposure");
        blackWhiteCheck = checkbox("B&W");
        body.addView(autoExposureCheck, new LinearLayout.LayoutParams(-1, -2));
        body.addView(blackWhiteCheck, new LinearLayout.LayoutParams(-1, -2));
        streamModesTextView = text(streamModesText, 11, false);
        streamModesTextView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(streamModesTextView, new LinearLayout.LayoutParams(-1, -2));
        statusText = text("Open UVC camera to query direct libuvc controls.", 11, false);
        body.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(230)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(panel, params);
        wireControls();
    }

    private void wireControls() {
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && !loading) { readWidgets(); updateLabels(); applyControls("control change"); saveSettings(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { readWidgets(); applyControls("control released"); saveSettings(); }
        };
        brightnessSeek.setOnSeekBarChangeListener(l); contrastSeek.setOnSeekBarChangeListener(l); gainSeek.setOnSeekBarChangeListener(l); exposureSeek.setOnSeekBarChangeListener(l);
        autoExposureCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean checked) { if (!loading) { readWidgets(); updateExposureState(); applyControls("auto exposure change"); saveSettings(); } } });
        blackWhiteCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean checked) { if (!loading) { readWidgets(); applyControls("B&W change"); saveSettings(); } } });
        setControlAvailability(false);
    }

    private SeekBar addSlider(LinearLayout parent, String label, int initial, TextView value) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(label, 12, false), new LinearLayout.LayoutParams(0, -2, 1)); row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seek = new SeekBar(context); seek.setMax(100); seek.setProgress(initial); parent.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        return seek;
    }

    private void loadSettings() {
        SharedPreferences p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        brightness = clamp(p.getInt("brightness", 50), 0, 100); contrast = clamp(p.getInt("contrast", 50), 0, 100); gain = clamp(p.getInt("gain", 0), 0, 100); exposure = clamp(p.getInt("exposure", 50), 0, 100); autoExposure = p.getBoolean("ae", true); blackWhite = p.getBoolean("bw", false);
        if (panel != null) { loading = true; brightnessSeek.setProgress(brightness); contrastSeek.setProgress(contrast); gainSeek.setProgress(gain); exposureSeek.setProgress(exposure); autoExposureCheck.setChecked(autoExposure); blackWhiteCheck.setChecked(blackWhite); loading = false; updateLabels(); updateExposureState(); }
        applyDisplayFilter();
    }

    private void saveSettings() { context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putInt("brightness", brightness).putInt("contrast", contrast).putInt("gain", gain).putInt("exposure", exposure).putBoolean("ae", autoExposure).putBoolean("bw", blackWhite).apply(); }
    private void readWidgets() { brightness = brightnessSeek.getProgress(); contrast = contrastSeek.getProgress(); gain = gainSeek.getProgress(); exposure = exposureSeek.getProgress(); autoExposure = autoExposureCheck.isChecked(); blackWhite = blackWhiteCheck.isChecked(); }
    private void updateLabels() { brightnessText.setText(String.format(Locale.US, "%d%%", brightness)); contrastText.setText(String.format(Locale.US, "%d%%", contrast)); gainText.setText(String.format(Locale.US, "%d%%", gain)); exposureText.setText(String.format(Locale.US, "%d%% %s", exposure, autoExposure ? "auto" : "manual")); }

    private void setControlAvailability(boolean open) {
        brightnessSupported = activeCamera != null && checkSupport(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = activeCamera != null && checkSupport(UVCCamera.PU_CONTRAST);
        gainSupported = activeCamera != null && checkSupport(UVCCamera.PU_GAIN);
        exposureSupported = exposureBridge != null && exposureBridge.supportsAbsoluteExposure();
        autoExposureSupported = exposureBridge != null && exposureBridge.supportsAutoExposureMode();
        if (panel == null) return;
        brightnessSeek.setEnabled(open && brightnessSupported);
        contrastSeek.setEnabled(open && contrastSupported);
        gainSeek.setEnabled(open && gainSupported);
        autoExposureCheck.setEnabled(open && autoExposureSupported);
        if (open && exposureBridge != null) queryExposure();
        updateExposureState();
        if (streamModesTextView != null) streamModesTextView.setText(streamModesText);
        statusText.setText(!open ? "Open UVC camera to query direct libuvc controls." : String.format(Locale.US, "Direct libuvc: %dx%d %s. brightness=%s, contrast=%s, gain=%s, exposure=%s, auto=%s, %s.", previewWidth, previewHeight, formatName(previewFormat), brightnessSupported, contrastSupported, gainSupported, exposureSupported, autoExposureSupported, exposureBridge == null ? "no exposure bridge" : exposureBridge.describeExposureRange()));
    }

    private boolean checkSupport(long flag) { try { return activeCamera.checkSupportFlag(flag); } catch (Throwable ignored) { return false; } }
    private void queryExposure() { loading = true; if (autoExposureSupported) { autoExposure = exposureBridge.isAutoExposureEnabled(); autoExposureCheck.setChecked(autoExposure); } if (exposureSupported) { int v = exposureBridge.getExposurePercent(); if (v >= 0) { exposure = v; exposureSeek.setProgress(v); } } loading = false; updateLabels(); }
    private void updateExposureState() { if (exposureSeek != null) exposureSeek.setEnabled(activeCamera != null && exposureSupported && !autoExposure); }

    private void applyControls(String reason) {
        applyDisplayFilter();
        if (activeCamera == null) { setStatus("Controls applied (" + reason + "). Waiting for direct UVC camera."); return; }
        boolean b = false, c = false, g = false, ae = false, e = false;
        try { if (brightnessSupported) { activeCamera.setBrightness(brightness); b = true; } } catch (Throwable ignored) { }
        try { if (contrastSupported) { activeCamera.setContrast(contrast); c = true; } } catch (Throwable ignored) { }
        try { if (gainSupported) { activeCamera.setGain(gain); g = true; } } catch (Throwable ignored) { }
        if (exposureBridge != null) { if (autoExposureSupported) ae = exposureBridge.setAutoExposureEnabled(autoExposure); if (exposureSupported && !autoExposure) e = exposureBridge.setExposurePercent(exposure); }
        setStatus("Controls applied (" + reason + "). brightness=" + b + "; contrast=" + c + "; gain=" + g + "; AE=" + ae + "; exposure=" + e + ".");
    }

    private void applyDisplayFilter() {
        float c = Math.max(0.05f, contrast / 50f), b = (brightness - 50) * 2.55f;
        ColorMatrix cm = new ColorMatrix(); if (blackWhite) cm.setSaturation(0);
        cm.postConcat(new ColorMatrix(new float[]{c,0,0,0,b, 0,c,0,0,b, 0,0,c,0,b, 0,0,0,1,0}));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColorFilter(new ColorMatrixColorFilter(cm)); previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint); previewView.invalidate();
    }

    private void updateCollapsed() { if (panel == null) return; for (int i = 1; i < panel.getChildCount(); i++) panel.getChildAt(i).setVisibility(collapsed ? View.GONE : View.VISIBLE); ((TextView) panel.getChildAt(0)).setText(collapsed ? "Camera image controls  ▼" : "Camera image controls  ▲"); }
    private void removeControlsPanel() { if (panel == null) return; ViewGroup p = (ViewGroup) panel.getParent(); if (p != null) p.removeView(panel); panel = null; }
    private void setStatus(String s) { if (statusText != null) statusText.setText(s); notifyStatus(s); }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(context); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(243,245,247)); v.setPadding(0, dp(2), 0, dp(2)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private TextView valueText() { TextView v = text("—", 11, false); v.setGravity(Gravity.END); return v; }
    private CheckBox checkbox(String label) { CheckBox c = new CheckBox(context); c.setText(label); c.setTextColor(Color.rgb(243,245,247)); c.setTextSize(12); return c; }

    private void remember(UsbDevice d) { if (d != null) devicesById.put(d.getDeviceId(), d); }
    private UsbDevice firstDevice() { for (UsbDevice d : devicesById.values()) return d; return null; }
    private boolean isUvc(UsbDevice d) { if (d == null) return false; for (int i = 0; i < d.getInterfaceCount(); i++) { UsbInterface f = d.getInterface(i); if (f != null && f.getInterfaceClass() == USB_VIDEO_CLASS) return true; } return false; }
    private String brief(UsbDevice d) { return String.format(Locale.US, "VID %04x / PID %04x", d.getVendorId(), d.getProductId()); }
    private String longDevice(UsbDevice d) { return String.format(Locale.US, "VID %04x / PID %04x, interfaces=%d, name=%s", d.getVendorId(), d.getProductId(), d.getInterfaceCount(), d.getDeviceName()); }
    private String formatName(int format) { return format == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG/compressed" : "YUYV/uncompressed"; }
    private String describeThrowable(Throwable t) { String m = t.getMessage(); return t.getClass().getSimpleName() + ": " + (m == null || m.trim().isEmpty() ? "no detail message" : m); }
    private void notifyStatus(final String s) { mainHandler.post(new Runnable() { @Override public void run() { if (listener != null) listener.onUvcStatusChanged(s); } }); }
    private int dp(int v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static final class SizeSelection {
        final int width;
        final int height;
        final int format;
        SizeSelection(int width, int height, int format) { this.width = width; this.height = height; this.format = format; }
    }
}
