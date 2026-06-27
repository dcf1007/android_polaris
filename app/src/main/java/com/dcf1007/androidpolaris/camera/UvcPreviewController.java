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

import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Direct UVC preview and camera-control backend.
 *
 * <p>The JitPack libuvc artifact used by this app exposes the original Serenegiant
 * package names, so this controller uses {@link USBMonitor} and {@link UVCCamera}
 * directly. It does not use the AUSBC wrapper.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int MANUAL_EXPOSURE_MODE = 1;
    private static final int AUTO_EXPOSURE_MODE = 2;
    private static final int STREAM_TYPE_YUYV = 4;
    private static final int STREAM_TYPE_MJPEG = 6;
    private static final int MAX_STREAM_MODES_TO_DISPLAY_PER_FORMAT = 8;
    private static final long PREVIEW_SURFACE_RETRY_DELAY_MS = 250L;
    private static final String CAMERA_CONTROL_SETTINGS = "android_polaris_uvc_camera_controls";
    private static final float DEFAULT_PREVIEW_ASPECT_RATIO = 4.0f / 3.0f;

    public enum PreviewFitMode {
        COVER,
        CONTAIN,
        STRETCH
    }

    public interface Listener {
        void onUvcStatusChanged(String statusText);
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

    private boolean usbMonitorRegistered;
    private boolean openRetryScheduled;
    private PreviewFitMode previewFitMode = PreviewFitMode.COVER;

    private int activePreviewWidth;
    private int activePreviewHeight;
    private int activePreviewFormat = UVCCamera.FRAME_FORMAT_MJPEG;
    private String supportedStreamModesText = "Stream modes: open the UVC camera to query formats.";

    private LinearLayout controlPanel;
    private TextView cameraStatusTextView;
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
    private boolean controlPanelCollapsed;
    private boolean loadingSavedControls;

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
        buildControlPanel();
        loadCameraControlSettings();
        applyPreviewFitModeLater();
    }

    public boolean register() {
        if (usbMonitorRegistered) {
            return true;
        }
        try {
            usbMonitor.register();
            usbMonitorRegistered = true;
            refreshDetectedUvcDeviceCache();
            notifyStatus("Direct libuvc monitor registered. Tap Open USB UVC camera if permission is not requested automatically.");
            return true;
        } catch (Throwable throwable) {
            usbMonitorRegistered = false;
            notifyStatus("Direct libuvc monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    public void unregister() {
        closeActiveCamera();
        if (!usbMonitorRegistered) {
            return;
        }
        try {
            usbMonitor.unregister();
        } catch (Throwable throwable) {
            notifyStatus("USB monitor unregister warning: " + describeThrowable(throwable));
        } finally {
            usbMonitorRegistered = false;
        }
    }

    public void destroy() {
        closeActiveCamera();
        removeControlPanel();
        detectedUvcDevicesById.clear();
        try {
            usbMonitor.destroy();
        } catch (Throwable throwable) {
            notifyStatus("USB monitor destroy warning: " + describeThrowable(throwable));
        }
        previewContainer.removeAllViews();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) {
            return;
        }
        refreshDetectedUvcDeviceCache();
        UsbDevice firstDevice = getFirstDetectedUvcDevice();
        if (firstDevice == null) {
            notifyStatus("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }

        pendingOpenDevice = firstDevice;
        notifyStatus("Requesting USB permission for " + describeDeviceBrief(firstDevice) + "…");
        try {
            if (usbMonitor.requestPermission(firstDevice)) {
                notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
            }
        } catch (Throwable throwable) {
            notifyStatus("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public String describeConnectedUvcDevices() {
        refreshDetectedUvcDeviceCache();
        if (detectedUvcDevicesById.isEmpty()) {
            return "No raw USB UVC devices detected.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(detectedUvcDevicesById.size()).append(" raw UVC device(s) detected:\n");
        for (UsbDevice device : detectedUvcDevicesById.values()) {
            builder.append(describeDeviceLong(device)).append('\n');
        }
        builder.append("Preview source: direct libuvc/UVCCamera.");
        if (activeCamera != null) {
            builder.append("\nActive preview: ")
                    .append(activePreviewWidth).append('×').append(activePreviewHeight)
                    .append(' ').append(formatName(activePreviewFormat)).append('.');
            builder.append("\nExposure abs=").append(exposureSupported)
                    .append(", auto=").append(autoExposureSupported).append('.');
            builder.append('\n').append(supportedStreamModesText);
        }
        return builder.toString().trim();
    }

    public void closeActiveCamera() {
        try {
            if (activeCamera != null) {
                activeCamera.destroy();
            }
        } catch (Throwable throwable) {
            notifyStatus("Direct libuvc close warning: " + describeThrowable(throwable));
        } finally {
            activeCamera = null;
            activeControlBlock = null;
            clearNativeExposureAccess();
            activePreviewWidth = 0;
            activePreviewHeight = 0;
            activePreviewFormat = UVCCamera.FRAME_FORMAT_MJPEG;
            supportedStreamModesText = "Stream modes: open the UVC camera to query formats.";
            openRetryScheduled = false;
            updateControlAvailability(false);
        }
    }

    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    private void configurePreviewTextureView() {
        previewContainer.removeAllViews();
        previewContainer.addView(previewTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        previewContainer.setVisibility(View.VISIBLE);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                    applyPreviewFitMode();
                }
            }
        });
        previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                applyPreviewFitMode();
                notifyStatus("Preview surface ready: " + width + "×" + height + ".");
                if (pendingOpenDevice != null && activeControlBlock != null) {
                    openCameraWhenSurfaceIsReady(pendingOpenDevice, activeControlBlock);
                }
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                applyPreviewFitMode();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                closeActiveCamera();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                // Preview frames are rendered by libuvc directly into the texture.
            }
        });
    }

    private USBMonitor.OnDeviceConnectListener createUsbConnectionListener() {
        return new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice device) {
                if (isUvcVideoDevice(device)) {
                    rememberDetectedDevice(device);
                    notifyStatus("UVC attached: " + describeDeviceBrief(device) + ". Tap Open USB UVC camera to request permission.");
                }
            }

            @Override public void onDettach(UsbDevice device) {
                if (device != null) {
                    detectedUvcDevicesById.remove(device.getDeviceId());
                }
                closeActiveCamera();
                notifyStatus("UVC detached" + (device == null ? "." : ": " + device.getDeviceName()));
            }

            @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock, boolean createNew) {
                if (!isUvcVideoDevice(device) || controlBlock == null) {
                    return;
                }
                rememberDetectedDevice(device);
                pendingOpenDevice = device;
                activeControlBlock = controlBlock;
                openCameraWhenSurfaceIsReady(device, controlBlock);
            }

            @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                closeActiveCamera();
                notifyStatus("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }

            @Override public void onCancel(UsbDevice device) {
                pendingOpenDevice = null;
                notifyStatus("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
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
                openDirectUvcCamera(device, controlBlock);
            }
        });
    }

    private void openDirectUvcCamera(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
        closeActiveCamera();
        try {
            UVCCamera camera = new UVCCamera();
            camera.open(controlBlock);
            String supportedSizeJson = camera.getSupportedSize();
            supportedStreamModesText = buildSupportedStreamModesText(supportedSizeJson);
            updateStreamModesTextView();

            PreviewMode selectedMode = chooseDefaultPreviewMode(supportedSizeJson);
            if (selectedMode == null) {
                throw new IllegalStateException("No supported UVC preview size reported by camera");
            }

            camera.setPreviewSize(selectedMode.width, selectedMode.height, 1, 31,
                    selectedMode.frameFormat, UVCCamera.DEFAULT_BANDWIDTH);
            camera.setPreviewTexture(previewTextureView.getSurfaceTexture());
            camera.startPreview();
            camera.updateCameraParams();

            activeCamera = camera;
            activeControlBlock = controlBlock;
            activePreviewWidth = selectedMode.width;
            activePreviewHeight = selectedMode.height;
            activePreviewFormat = selectedMode.frameFormat;
            prepareNativeExposureAccess();

            updateControlAvailability(true);
            applyCameraControls("camera opened");
            applyPreviewFitMode();
            notifyStatus("Direct libuvc preview opened for " + describeDeviceBrief(device)
                    + " at " + selectedMode.width + "×" + selectedMode.height
                    + " " + formatName(selectedMode.frameFormat) + ".");
        } catch (Throwable throwable) {
            activeCamera = null;
            clearNativeExposureAccess();
            updateControlAvailability(false);
            notifyStatus("Failed to open direct libuvc preview: " + describeThrowable(throwable));
        }
    }

    private PreviewMode chooseDefaultPreviewMode(String supportedSizeJson) {
        PreviewMode yuyvMode = choosePreferredModeForFormat(supportedSizeJson, UVCCamera.FRAME_FORMAT_YUYV);
        return yuyvMode != null ? yuyvMode : choosePreferredModeForFormat(supportedSizeJson, UVCCamera.FRAME_FORMAT_MJPEG);
    }

    private PreviewMode choosePreferredModeForFormat(String supportedSizeJson, int frameFormat) {
        List<Size> sizes = getSizesForFormat(supportedSizeJson, frameFormat);
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Size selectedSize = sizes.get(0);
        for (Size size : sizes) {
            if (size.width == 640 && size.height == 480) {
                selectedSize = size;
                break;
            }
        }
        return new PreviewMode(selectedSize.width, selectedSize.height, frameFormat);
    }

    private String buildSupportedStreamModesText(String supportedSizeJson) {
        StringBuilder builder = new StringBuilder("Supported stream modes:");
        appendStreamModesForFormat(builder, supportedSizeJson, UVCCamera.FRAME_FORMAT_YUYV, "YUYV / uncompressed");
        appendStreamModesForFormat(builder, supportedSizeJson, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG / compressed");
        return builder.toString();
    }

    private void appendStreamModesForFormat(StringBuilder builder, String supportedSizeJson, int frameFormat, String label) {
        List<Size> sizes = getSizesForFormat(supportedSizeJson, frameFormat);
        builder.append('\n').append(label).append(':');
        if (sizes == null || sizes.isEmpty()) {
            builder.append(" none reported");
            return;
        }
        int displayed = 0;
        for (Size size : sizes) {
            if (displayed >= MAX_STREAM_MODES_TO_DISPLAY_PER_FORMAT) {
                builder.append(" …");
                break;
            }
            builder.append('\n').append("  • ").append(size.width).append('×').append(size.height);
            String fpsSummary = summarizeFrameRates(size);
            if (!fpsSummary.isEmpty()) {
                builder.append(" @ ").append(fpsSummary);
            }
            displayed++;
        }
    }

    private List<Size> getSizesForFormat(String supportedSizeJson, int frameFormat) {
        int streamType = frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? STREAM_TYPE_MJPEG : STREAM_TYPE_YUYV;
        try {
            return UVCCamera.getSupportedSize(streamType, supportedSizeJson);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String summarizeFrameRates(Size size) {
        if (size == null || size.fps == null || size.fps.length == 0) {
            return "";
        }
        float minimumFps = Float.MAX_VALUE;
        float maximumFps = 0.0f;
        for (float fps : size.fps) {
            if (fps <= 0.0f) {
                continue;
            }
            minimumFps = Math.min(minimumFps, fps);
            maximumFps = Math.max(maximumFps, fps);
        }
        if (maximumFps <= 0.0f || minimumFps == Float.MAX_VALUE) {
            return "";
        }
        return Math.abs(maximumFps - minimumFps) < 0.1f
                ? String.format(Locale.US, "%.0f fps", maximumFps)
                : String.format(Locale.US, "%.0f–%.0f fps", minimumFps, maximumFps);
    }

    private void applyPreviewFitModeLater() {
        previewTextureView.postDelayed(new Runnable() {
            @Override public void run() {
                applyPreviewFitMode();
            }
        }, PREVIEW_SURFACE_RETRY_DELAY_MS);
    }

    private void applyPreviewFitMode() {
        previewTextureView.post(new Runnable() {
            @Override public void run() {
                applyPreviewFitModeNow();
            }
        });
    }

    private void applyPreviewFitModeNow() {
        int containerWidth = previewContainer.getWidth();
        int containerHeight = previewContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            return;
        }

        float sourceAspectRatio = activePreviewWidth > 0 && activePreviewHeight > 0
                ? activePreviewWidth / (float) activePreviewHeight
                : DEFAULT_PREVIEW_ASPECT_RATIO;
        int targetWidth = containerWidth;
        int targetHeight = containerHeight;

        if (previewFitMode != PreviewFitMode.STRETCH) {
            boolean containerWiderThanSource = containerWidth / (float) containerHeight > sourceAspectRatio;
            if (previewFitMode == PreviewFitMode.CONTAIN) {
                if (containerWiderThanSource) {
                    targetHeight = containerHeight;
                    targetWidth = Math.round(targetHeight * sourceAspectRatio);
                } else {
                    targetWidth = containerWidth;
                    targetHeight = Math.round(targetWidth / sourceAspectRatio);
                }
            } else {
                if (containerWiderThanSource) {
                    targetWidth = containerWidth;
                    targetHeight = Math.round(targetWidth / sourceAspectRatio);
                } else {
                    targetHeight = containerHeight;
                    targetWidth = Math.round(targetHeight * sourceAspectRatio);
                }
            }
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewTextureView.getLayoutParams();
        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);
        if (params.width != targetWidth || params.height != targetHeight || params.gravity != Gravity.CENTER) {
            params.width = targetWidth;
            params.height = targetHeight;
            params.gravity = Gravity.CENTER;
            previewTextureView.setLayoutParams(params);
        }
        previewTextureView.setTranslationX(0.0f);
        previewTextureView.setTranslationY(0.0f);
        previewTextureView.setScaleX(1.0f);
        previewTextureView.setScaleY(1.0f);
        previewTextureView.setPivotX(targetWidth / 2.0f);
        previewTextureView.setPivotY(targetHeight / 2.0f);
    }

    private void scheduleOpenRetry(final UsbDevice device, final USBMonitor.UsbControlBlock controlBlock) {
        if (openRetryScheduled) {
            return;
        }
        openRetryScheduled = true;
        previewTextureView.postDelayed(new Runnable() {
            @Override public void run() {
                openRetryScheduled = false;
                if (previewTextureView.isAvailable() && previewTextureView.getSurfaceTexture() != null) {
                    openDirectUvcCamera(device, controlBlock);
                } else {
                    notifyStatus("Preview surface still unavailable. Preview view size: "
                            + previewTextureView.getWidth() + "×" + previewTextureView.getHeight() + ".");
                }
            }
        }, PREVIEW_SURFACE_RETRY_DELAY_MS);
    }

    private void refreshDetectedUvcDeviceCache() {
        try {
            for (UsbDevice device : usbMonitor.getDeviceList()) {
                if (isUvcVideoDevice(device)) {
                    rememberDetectedDevice(device);
                }
            }
        } catch (Throwable throwable) {
            notifyStatus("USB device scan failed: " + describeThrowable(throwable));
        }
    }

    private void buildControlPanel() {
        if (!(context instanceof Activity)) {
            return;
        }
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        if (root == null) {
            return;
        }

        controlPanel = new LinearLayout(context);
        controlPanel.setOrientation(LinearLayout.VERTICAL);
        controlPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        controlPanel.setBackgroundColor(Color.argb(235, 16, 18, 24));

        TextView titleView = createTextView("Camera image controls  ▲", 13, true);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                controlPanelCollapsed = !controlPanelCollapsed;
                updateControlPanelCollapsedState();
            }
        });
        controlPanel.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        brightnessSeekBar = addControlSlider(body, "Brightness", 50, brightnessValueTextView = createValueTextView());
        contrastSeekBar = addControlSlider(body, "Contrast", 50, contrastValueTextView = createValueTextView());
        gainSeekBar = addControlSlider(body, "Gain", 0, gainValueTextView = createValueTextView());
        exposureSeekBar = addControlSlider(body, "Exposure", 50, exposureValueTextView = createValueTextView());
        autoExposureCheckBox = createCheckBox("Auto exposure");
        blackWhiteCheckBox = createCheckBox("B&W");
        body.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(blackWhiteCheckBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        streamModesTextView = createTextView(supportedStreamModesText, 11, false);
        streamModesTextView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(streamModesTextView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cameraStatusTextView = createTextView("Open UVC camera to query direct libuvc controls.", 11, false);
        body.addView(cameraStatusTextView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        controlPanel.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(controlPanel, params);
        wireControlPanelActions();
    }

    private void wireControlPanelActions() {
        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || loadingSavedControls) {
                    return;
                }
                readControlValuesFromWidgets();
                updateControlValueLabels();
                applyCameraControls("control change");
                saveCameraControlSettings();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                readControlValuesFromWidgets();
                applyCameraControls("control released");
                saveCameraControlSettings();
            }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(sliderListener);
        contrastSeekBar.setOnSeekBarChangeListener(sliderListener);
        gainSeekBar.setOnSeekBarChangeListener(sliderListener);
        exposureSeekBar.setOnSeekBarChangeListener(sliderListener);

        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (loadingSavedControls) {
                    return;
                }
                readControlValuesFromWidgets();
                updateExposureWidgetEnabledState();
                applyCameraControls("auto exposure change");
                saveCameraControlSettings();
            }
        });
        blackWhiteCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (loadingSavedControls) {
                    return;
                }
                readControlValuesFromWidgets();
                applyCameraControls("B&W change");
                saveCameraControlSettings();
            }
        });
        updateControlAvailability(false);
    }

    private SeekBar addControlSlider(LinearLayout parent, String label, int initialValue, TextView valueView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(createTextView(label, 12, false), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valueView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(initialValue);
        parent.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return seekBar;
    }

    private void loadCameraControlSettings() {
        SharedPreferences preferences = context.getSharedPreferences(CAMERA_CONTROL_SETTINGS, Context.MODE_PRIVATE);
        brightnessPercent = clamp(preferences.getInt("brightness", 50), 0, 100);
        contrastPercent = clamp(preferences.getInt("contrast", 50), 0, 100);
        gainPercent = clamp(preferences.getInt("gain", 0), 0, 100);
        exposurePercent = clamp(preferences.getInt("exposure", 50), 0, 100);
        autoExposureEnabled = preferences.getBoolean("ae", true);
        blackWhiteEnabled = preferences.getBoolean("bw", false);
        if (controlPanel != null) {
            loadingSavedControls = true;
            brightnessSeekBar.setProgress(brightnessPercent);
            contrastSeekBar.setProgress(contrastPercent);
            gainSeekBar.setProgress(gainPercent);
            exposureSeekBar.setProgress(exposurePercent);
            autoExposureCheckBox.setChecked(autoExposureEnabled);
            blackWhiteCheckBox.setChecked(blackWhiteEnabled);
            loadingSavedControls = false;
            updateControlValueLabels();
            updateExposureWidgetEnabledState();
        }
        applyDisplaySideImageFilter();
    }

    private void saveCameraControlSettings() {
        context.getSharedPreferences(CAMERA_CONTROL_SETTINGS, Context.MODE_PRIVATE).edit()
                .putInt("brightness", brightnessPercent)
                .putInt("contrast", contrastPercent)
                .putInt("gain", gainPercent)
                .putInt("exposure", exposurePercent)
                .putBoolean("ae", autoExposureEnabled)
                .putBoolean("bw", blackWhiteEnabled)
                .apply();
    }

    private void readControlValuesFromWidgets() {
        brightnessPercent = brightnessSeekBar.getProgress();
        contrastPercent = contrastSeekBar.getProgress();
        gainPercent = gainSeekBar.getProgress();
        exposurePercent = exposureSeekBar.getProgress();
        autoExposureEnabled = autoExposureCheckBox.isChecked();
        blackWhiteEnabled = blackWhiteCheckBox.isChecked();
    }

    private void updateControlValueLabels() {
        brightnessValueTextView.setText(String.format(Locale.US, "%d%%", brightnessPercent));
        contrastValueTextView.setText(String.format(Locale.US, "%d%%", contrastPercent));
        gainValueTextView.setText(String.format(Locale.US, "%d%%", gainPercent));
        exposureValueTextView.setText(String.format(Locale.US, "%d%% %s",
                exposurePercent, autoExposureEnabled ? "auto" : "manual"));
    }

    private void updateControlAvailability(boolean cameraOpen) {
        brightnessSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_BRIGHTNESS);
        contrastSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_CONTRAST);
        gainSupported = activeCamera != null && checkControlSupport(UVCCamera.PU_GAIN);
        exposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE_ABS);
        autoExposureSupported = nativeExposureAccessReady && checkControlSupport(UVCCamera.CTRL_AE);
        if (controlPanel == null) {
            return;
        }
        brightnessSeekBar.setEnabled(cameraOpen && brightnessSupported);
        contrastSeekBar.setEnabled(cameraOpen && contrastSupported);
        gainSeekBar.setEnabled(cameraOpen && gainSupported);
        autoExposureCheckBox.setEnabled(cameraOpen && autoExposureSupported);
        if (cameraOpen && nativeExposureAccessReady) {
            queryExposureFromDevice();
        }
        updateExposureWidgetEnabledState();
        updateStreamModesTextView();
        updateCameraStatusText(cameraOpen);
    }

    private boolean checkControlSupport(long supportFlag) {
        try {
            return activeCamera.checkSupportFlag(supportFlag);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void queryExposureFromDevice() {
        loadingSavedControls = true;
        if (autoExposureSupported) {
            autoExposureEnabled = isAutoExposureCurrentlyEnabled();
            autoExposureCheckBox.setChecked(autoExposureEnabled);
        }
        if (exposureSupported) {
            int currentExposurePercent = getExposurePercentFromDevice();
            if (currentExposurePercent >= 0) {
                exposurePercent = currentExposurePercent;
                exposureSeekBar.setProgress(currentExposurePercent);
            }
        }
        loadingSavedControls = false;
        updateControlValueLabels();
    }

    private void updateExposureWidgetEnabledState() {
        if (exposureSeekBar != null) {
            exposureSeekBar.setEnabled(activeCamera != null && exposureSupported && !autoExposureEnabled);
        }
    }

    private void updateStreamModesTextView() {
        if (streamModesTextView != null) {
            streamModesTextView.setText(supportedStreamModesText);
        }
    }

    private void updateCameraStatusText(boolean cameraOpen) {
        if (cameraStatusTextView == null) {
            return;
        }
        if (!cameraOpen) {
            cameraStatusTextView.setText("Open UVC camera to query direct libuvc controls.");
            return;
        }
        cameraStatusTextView.setText(String.format(Locale.US,
                "Direct libuvc: %dx%d %s. brightness=%s, contrast=%s, gain=%s, exposure=%s, auto=%s, %s.",
                activePreviewWidth,
                activePreviewHeight,
                formatName(activePreviewFormat),
                brightnessSupported,
                contrastSupported,
                gainSupported,
                exposureSupported,
                autoExposureSupported,
                nativeExposureAccessReady ? describeExposureRange() : "native exposure access unavailable"));
    }

    private void applyCameraControls(String reason) {
        applyDisplaySideImageFilter();
        if (activeCamera == null) {
            setControlStatus("Controls applied (" + reason + "). Waiting for direct UVC camera.");
            return;
        }
        boolean brightnessApplied = false;
        boolean contrastApplied = false;
        boolean gainApplied = false;
        boolean autoExposureApplied = false;
        boolean exposureApplied = false;
        try {
            if (brightnessSupported) {
                activeCamera.setBrightness(brightnessPercent);
                brightnessApplied = true;
            }
        } catch (Throwable ignored) { }
        try {
            if (contrastSupported) {
                activeCamera.setContrast(contrastPercent);
                contrastApplied = true;
            }
        } catch (Throwable ignored) { }
        try {
            if (gainSupported) {
                activeCamera.setGain(gainPercent);
                gainApplied = true;
            }
        } catch (Throwable ignored) { }
        if (nativeExposureAccessReady) {
            if (autoExposureSupported) {
                autoExposureApplied = setAutoExposureEnabled(autoExposureEnabled);
            }
            if (exposureSupported && !autoExposureEnabled) {
                exposureApplied = setExposurePercentOnDevice(exposurePercent);
            }
        }
        setControlStatus("Controls applied (" + reason + "). brightness=" + brightnessApplied
                + "; contrast=" + contrastApplied
                + "; gain=" + gainApplied
                + "; AE=" + autoExposureApplied
                + "; exposure=" + exposureApplied + ".");
    }

    private void prepareNativeExposureAccess() {
        clearNativeExposureAccess();
        try {
            Class<UVCCamera> cameraClass = UVCCamera.class;
            exposureNativePointerField = accessibleField(cameraClass, "mNativePtr");
            exposureMinimumField = accessibleField(cameraClass, "mExposureMin");
            exposureMaximumField = accessibleField(cameraClass, "mExposureMax");
            exposureDefaultField = accessibleField(cameraClass, "mExposureDef");
            exposureModeDefaultField = accessibleField(cameraClass, "mExposureModeDef");
            updateExposureLimitMethod = accessibleMethod(cameraClass, "nativeUpdateExposureLimit", long.class);
            getExposureMethod = accessibleMethod(cameraClass, "nativeGetExposure", long.class);
            setExposureMethod = accessibleMethod(cameraClass, "nativeSetExposure", long.class, int.class);
            updateExposureModeLimitMethod = accessibleMethod(cameraClass, "nativeUpdateExposureModeLimit", long.class);
            getExposureModeMethod = accessibleMethod(cameraClass, "nativeGetExposureMode", long.class);
            setExposureModeMethod = accessibleMethod(cameraClass, "nativeSetExposureMode", long.class, int.class);
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
        int minimum = exposureMinimumField.getInt(activeCamera);
        int maximum = exposureMaximumField.getInt(activeCamera);
        int range = Math.abs(maximum - minimum);
        return range <= 0 ? 0 : clamp(Math.round((rawExposure - minimum) * 100.0f / range), 0, 100);
    }

    private int percentToRawExposure(int percent) throws IllegalAccessException {
        int minimum = exposureMinimumField.getInt(activeCamera);
        int maximum = exposureMaximumField.getInt(activeCamera);
        return Math.round(minimum + (Math.abs(maximum - minimum) * clamp(percent, 0, 100) / 100.0f));
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
        if (blackWhiteEnabled) {
            colorMatrix.setSaturation(0.0f);
        }
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

    private void updateControlPanelCollapsedState() {
        if (controlPanel == null) {
            return;
        }
        for (int index = 1; index < controlPanel.getChildCount(); index++) {
            controlPanel.getChildAt(index).setVisibility(controlPanelCollapsed ? View.GONE : View.VISIBLE);
        }
        ((TextView) controlPanel.getChildAt(0)).setText(
                controlPanelCollapsed ? "Camera image controls  ▼" : "Camera image controls  ▲");
    }

    private void removeControlPanel() {
        if (controlPanel == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) controlPanel.getParent();
        if (parent != null) {
            parent.removeView(controlPanel);
        }
        controlPanel = null;
    }

    private void setControlStatus(String text) {
        if (cameraStatusTextView != null) {
            cameraStatusTextView.setText(text);
        }
        notifyStatus(text);
    }

    private TextView createTextView(String text, int sizeSp, boolean bold) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(Color.rgb(243, 245, 247));
        textView.setPadding(0, dp(2), 0, dp(2));
        if (bold) {
            textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return textView;
    }

    private TextView createValueTextView() {
        TextView textView = createTextView("—", 11, false);
        textView.setGravity(Gravity.END);
        return textView;
    }

    private CheckBox createCheckBox(String label) {
        CheckBox checkBox = new CheckBox(context);
        checkBox.setText(label);
        checkBox.setTextColor(Color.rgb(243, 245, 247));
        checkBox.setTextSize(12);
        return checkBox;
    }

    private void rememberDetectedDevice(UsbDevice device) {
        if (device != null) {
            detectedUvcDevicesById.put(device.getDeviceId(), device);
        }
    }

    private UsbDevice getFirstDetectedUvcDevice() {
        for (UsbDevice device : detectedUvcDevicesById.values()) {
            return device;
        }
        return null;
    }

    private boolean isUvcVideoDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface != null && usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) {
                return true;
            }
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

    private String formatName(int frameFormat) {
        return frameFormat == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG/compressed" : "YUYV/uncompressed";
    }

    private String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + ": "
                + (message == null || message.trim().isEmpty() ? "no detail message" : message);
    }

    private void notifyStatus(final String statusText) {
        mainThreadHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) {
                    listener.onUvcStatusChanged(statusText);
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PreviewMode {
        final int width;
        final int height;
        final int frameFormat;

        PreviewMode(int width, int height, int frameFormat) {
            this.width = width;
            this.height = height;
            this.frameFormat = frameFormat;
        }
    }
}
