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
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;

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

/**
 * UVC-only preview controller.
 *
 * <p>The preview is deliberately treated like the browser page's video element:
 * the parent video layer controls position, size, opacity and rotation, while
 * this controller controls only object-fit plus camera-stream parameters.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final long SURFACE_RECHECK_DELAY_MS = 250L;
    private static final String CAMERA_CONTROL_SETTINGS = "android_polaris_uvc_camera_controls";

    /**
     * Default UVC polar-scope camera frame aspect.
     *
     * <p>Most inexpensive OTG/UVC eyepiece cameras negotiate 640×480 or another
     * 4:3 mode. The fit menu needs a stable source aspect to behave like CSS
     * object-fit. Width/height sliders remain available for manual calibration
     * when a specific camera negotiates a different stream shape.</p>
     */
    private static final float DEFAULT_PREVIEW_SOURCE_ASPECT = 4.0f / 3.0f;

    private static final String[] FPS_ENTRIES = {"Default", "15 fps", "30 fps", "60 fps"};
    private static final int[] FPS_VALUES = {0, 15, 30, 60};

    public enum PreviewFitMode {
        COVER,
        CONTAIN,
        STRETCH
    }

    public interface Listener {
        void onUvcStatusChanged(String statusText);
    }

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

    private LinearLayout floatingControlsPanel;
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
    private Spinner fpsSpinner;
    private boolean controlsPanelCollapsed;
    private boolean isApplyingSavedCameraControls;

    private int brightnessValue = 50;
    private int contrastValue = 50;
    private int gainValue = 0;
    private int exposureValue = 50;
    private boolean autoExposure = true;
    private boolean blackWhite = false;
    private int requestedFps = 0;
    private Object activeBlackWhiteEffect;

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;
        this.previewView = new AspectRatioTextureView(context);
        configurePreviewSurfaceCallbacks();

        this.previewContainer.removeAllViews();
        this.previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        this.previewContainer.setVisibility(View.VISIBLE);
        this.previewContainer.setClipChildren(true);
        this.previewContainer.setClipToPadding(true);
        this.previewContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(
                    View view,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom) {
                if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                    applyPreviewFitMode();
                }
            }
        });
        this.previewContainer.requestLayout();
        this.previewView.requestLayout();
        this.uvcClient = new MultiCameraClient(context, createDeviceConnectionCallback());
        buildFloatingCameraControlsIfPossible();
        loadCameraControlSettings();
        scheduleSurfaceAvailabilityRecheck("initial preview view attach");
    }

    public boolean register() {
        if (isUsbMonitorRegistered) {
            return true;
        }
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
        if (!isUsbMonitorRegistered) {
            return;
        }
        try {
            uvcClient.unRegister();
        } catch (Throwable throwable) {
            notifyStatus("UVC monitor unregister warning: " + describeThrowable(throwable));
        } finally {
            isUsbMonitorRegistered = false;
        }
    }

    public void destroy() {
        closeActiveCamera();
        removeFloatingCameraControls();
        uvcCamerasByDeviceId.clear();
        detectedUvcDevicesById.clear();
        try {
            uvcClient.destroy();
        } catch (Throwable throwable) {
            notifyStatus("UVC destroy warning: " + describeThrowable(throwable));
        }
        previewContainer.removeAllViews();
    }

    public void requestPermissionAndOpenFirstCamera() {
        if (!register()) {
            return;
        }
        refreshDetectedDeviceCacheFromUsbHost();
        UsbDevice firstDevice = getFirstDetectedUvcDevice();
        if (firstDevice == null) {
            notifyStatus("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }
        pendingOpenDevice = firstDevice;
        notifyStatus("Requesting USB permission for " + describeDeviceBrief(firstDevice) + "…");
        try {
            boolean requestStarted = uvcClient.requestPermission(firstDevice);
            if (!requestStarted) {
                notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
            }
        } catch (Throwable throwable) {
            notifyStatus("USB permission request failed: " + describeThrowable(throwable));
        }
    }

    public String describeConnectedUvcDevices() {
        refreshDetectedDeviceCacheFromUsbHost();
        if (detectedUvcDevicesById.isEmpty()) {
            return "No raw USB UVC devices detected.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(detectedUvcDevicesById.size()).append(" raw UVC device(s) detected:\n");
        for (UsbDevice device : detectedUvcDevicesById.values()) {
            builder.append(describeDeviceLong(device)).append('\n');
        }
        builder.append("Preview source: USB OTG UVC backend only. Camera2 is not used. ")
                .append("Image controls are attempted through AUSBC/UVC methods when available; brightness, contrast and B&W also have display-side fallback.");
        return builder.toString().trim();
    }

    public void closeActiveCamera() {
        if (activeUvcCamera != null) {
            removeBlackWhiteRenderEffectIfNeeded();
            try {
                activeUvcCamera.closeCamera();
            } catch (Throwable throwable) {
                notifyStatus("UVC close warning: " + describeThrowable(throwable));
            }
            activeUvcCamera = null;
        }
        isOpenAttemptScheduled = false;
    }

    /**
     * Sets the preview equivalent of the browser video element's object-fit value.
     */
    public void setPreviewFitMode(PreviewFitMode fitMode) {
        previewFitMode = fitMode == null ? PreviewFitMode.COVER : fitMode;
        applyPreviewFitMode();
    }

    private IDeviceConnectCallBack createDeviceConnectionCallback() {
        return new IDeviceConnectCallBack() {
            @Override
            public void onAttachDev(UsbDevice device) {
                if (!isUvcVideoDevice(device)) {
                    return;
                }
                rememberDetectedDevice(device);
                notifyStatus("UVC attached: " + describeDeviceBrief(device) + ". Tap Open USB UVC camera to request permission.");
            }

            @Override
            public void onDetachDec(UsbDevice device) {
                if (device == null) {
                    return;
                }
                detectedUvcDevicesById.remove(device.getDeviceId());
                MultiCameraClient.Camera removedCamera = uvcCamerasByDeviceId.remove(device.getDeviceId());
                if (removedCamera != null) {
                    try {
                        removedCamera.closeCamera();
                    } catch (Throwable throwable) {
                        notifyStatus("UVC detach close warning: " + describeThrowable(throwable));
                    }
                }
                if (pendingOpenDevice != null && pendingOpenDevice.getDeviceId() == device.getDeviceId()) {
                    pendingOpenDevice = null;
                }
                if (activeUvcCamera == removedCamera) {
                    activeUvcCamera = null;
                }
                notifyStatus("UVC detached: " + device.getDeviceName());
            }

            @Override
            public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                if (!isUvcVideoDevice(device) || controlBlock == null) {
                    return;
                }
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

            @Override
            public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock controlBlock) {
                closeActiveCamera();
                notifyStatus("UVC disconnected" + (device == null ? "." : ": " + device.getDeviceName()));
            }

            @Override
            public void onCancelDev(UsbDevice device) {
                pendingOpenDevice = null;
                notifyStatus("USB permission was cancelled" + (device == null ? "." : " for " + describeDeviceBrief(device) + "."));
            }
        };
    }

    private ICameraStateCallBack createCameraStateCallback() {
        return new ICameraStateCallBack() {
            @Override
            public void onCameraState(MultiCameraClient.Camera camera, ICameraStateCallBack.State state, String message) {
                if (state == ICameraStateCallBack.State.OPENED) {
                    applyPreviewFitMode();
                    applyAllCameraControls("camera opened");
                    notifyStatus("UVC preview opened. Camera image controls are available in the floating panel when the device/library supports them.");
                } else if (state == ICameraStateCallBack.State.CLOSED) {
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
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                isPreviewSurfaceAvailable = true;
                applyPreviewFitMode();
                notifyStatus("Preview surface ready: " + width + "×" + height + ".");
                if (activeUvcCamera != null && pendingOpenDevice != null) {
                    openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                applyPreviewFitMode();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                isPreviewSurfaceAvailable = false;
                closeActiveCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                // No per-frame processing is required.
            }
        });
    }

    private void applyPreviewFitMode() {
        previewView.post(new Runnable() {
            @Override public void run() { applyPreviewFitModeNow(); }
        });
    }

    private void applyPreviewFitModeNow() {
        int containerWidth = previewContainer.getWidth();
        int containerHeight = previewContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            return;
        }

        int targetWidth = containerWidth;
        int targetHeight = containerHeight;
        if (previewFitMode != PreviewFitMode.STRETCH) {
            float containerAspect = containerWidth / (float) containerHeight;
            boolean containerIsWiderThanSource = containerAspect > DEFAULT_PREVIEW_SOURCE_ASPECT;

            if (previewFitMode == PreviewFitMode.CONTAIN) {
                if (containerIsWiderThanSource) {
                    targetHeight = containerHeight;
                    targetWidth = Math.round(targetHeight * DEFAULT_PREVIEW_SOURCE_ASPECT);
                } else {
                    targetWidth = containerWidth;
                    targetHeight = Math.round(targetWidth / DEFAULT_PREVIEW_SOURCE_ASPECT);
                }
            } else {
                if (containerIsWiderThanSource) {
                    targetWidth = containerWidth;
                    targetHeight = Math.round(targetWidth / DEFAULT_PREVIEW_SOURCE_ASPECT);
                } else {
                    targetHeight = containerHeight;
                    targetWidth = Math.round(targetHeight * DEFAULT_PREVIEW_SOURCE_ASPECT);
                }
            }
        }

        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        if (params.width != targetWidth || params.height != targetHeight || params.gravity != Gravity.CENTER) {
            params.width = targetWidth;
            params.height = targetHeight;
            params.gravity = Gravity.CENTER;
            previewView.setLayoutParams(params);
        }
        previewView.setTranslationX(0.0f);
        previewView.setTranslationY(0.0f);
        previewView.setScaleX(1.0f);
        previewView.setScaleY(1.0f);
        previewView.setPivotX(targetWidth / 2.0f);
        previewView.setPivotY(targetHeight / 2.0f);
        previewContainer.invalidate();
    }

    private void openCameraWhenPreviewSurfaceIsReady(MultiCameraClient.Camera camera, UsbDevice device) {
        refreshPreviewSurfaceAvailability();
        if (!isPreviewSurfaceAvailable) {
            notifyStatus("UVC permission granted for " + describeDeviceBrief(device)
                    + ", waiting for preview surface. Current preview view size: "
                    + previewView.getWidth() + "×" + previewView.getHeight() + ".");
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

    private void refreshPreviewSurfaceAvailability() {
        isPreviewSurfaceAvailable = previewView.isAvailable();
    }

    private void scheduleSurfaceAvailabilityRecheck(String reason) {
        previewView.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshPreviewSurfaceAvailability();
                applyPreviewFitMode();
                if (isPreviewSurfaceAvailable) {
                    notifyStatus("Preview surface ready after " + reason + ".");
                    if (activeUvcCamera != null && pendingOpenDevice != null) {
                        openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
                    }
                }
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private void scheduleOpenAttemptAfterSurfaceRecheck(MultiCameraClient.Camera camera, UsbDevice device) {
        if (isOpenAttemptScheduled) {
            return;
        }
        isOpenAttemptScheduled = true;
        previewView.postDelayed(new Runnable() {
            @Override
            public void run() {
                isOpenAttemptScheduled = false;
                refreshPreviewSurfaceAvailability();
                applyPreviewFitMode();
                if (isPreviewSurfaceAvailable) {
                    openCameraWhenPreviewSurfaceIsReady(camera, device);
                } else {
                    notifyStatus("Preview surface still unavailable. Preview view size: "
                            + previewView.getWidth() + "×" + previewView.getHeight()
                            + ". The preview panel must be visible before UVC video can start.");
                }
            }
        }, SURFACE_RECHECK_DELAY_MS);
    }

    private CameraRequest createPreviewRequest() {
        CameraRequest.Builder builder = new CameraRequest.Builder()
                .setContinuousAFModel(true)
                .setContinuousAutoModel(true);
        if (requestedFps > 0) {
            boolean applied = tryInvokeBuilderInt(builder, requestedFps,
                    "setPreviewFrameRate", "setFrameRate", "setFps", "setPreviewFps", "setPreviewFPS");
            if (!applied) {
                setControlStatus("FPS request " + requestedFps + " could not be applied by this AUSBC build; default stream FPS will be used.");
            }
        }
        return builder.create();
    }

    private MultiCameraClient.Camera getOrCreateUvcCamera(UsbDevice device) {
        MultiCameraClient.Camera existingCamera = uvcCamerasByDeviceId.get(device.getDeviceId());
        if (existingCamera != null) {
            return existingCamera;
        }
        MultiCameraClient.Camera createdCamera = new MultiCameraClient.Camera(context, device);
        uvcCamerasByDeviceId.put(device.getDeviceId(), createdCamera);
        return createdCamera;
    }

    private void refreshDetectedDeviceCacheFromUsbHost() {
        for (UsbDevice device : getUvcDevicesFromClientSafely()) {
            rememberDetectedDevice(device);
        }
    }

    private List<UsbDevice> getUvcDevicesFromClientSafely() {
        List<UsbDevice> filteredDevices = new ArrayList<>();
        List<UsbDevice> allUsbDevices;
        try {
            allUsbDevices = uvcClient.getDeviceList(null);
        } catch (Throwable throwable) {
            notifyStatus("USB device scan failed: " + describeThrowable(throwable));
            return filteredDevices;
        }
        if (allUsbDevices == null) {
            return filteredDevices;
        }
        for (UsbDevice device : allUsbDevices) {
            if (isUvcVideoDevice(device)) {
                filteredDevices.add(device);
            }
        }
        return filteredDevices;
    }

    private void buildFloatingCameraControlsIfPossible() {
        if (!(context instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) context;
        FrameLayout decorContent = activity.findViewById(android.R.id.content);
        if (decorContent == null) {
            return;
        }

        floatingControlsPanel = new LinearLayout(context);
        floatingControlsPanel.setOrientation(LinearLayout.VERTICAL);
        floatingControlsPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        floatingControlsPanel.setBackgroundColor(Color.argb(235, 16, 18, 24));

        TextView title = createControlText("Camera image controls  ▲", 13, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                controlsPanelCollapsed = !controlsPanelCollapsed;
                updateControlsPanelCollapsedState();
            }
        });
        floatingControlsPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(controls, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        brightnessSeekBar = addCameraSlider(controls, "Brightness", 50, brightnessValueTextView = createControlValueText());
        contrastSeekBar = addCameraSlider(controls, "Contrast", 50, contrastValueTextView = createControlValueText());
        gainSeekBar = addCameraSlider(controls, "Gain", 0, gainValueTextView = createControlValueText());
        exposureSeekBar = addCameraSlider(controls, "Exposure", 50, exposureValueTextView = createControlValueText());

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        autoExposureCheckBox = new CheckBox(context);
        autoExposureCheckBox.setText("Auto exposure");
        autoExposureCheckBox.setTextColor(Color.rgb(243, 245, 247));
        autoExposureCheckBox.setTextSize(12);
        blackWhiteCheckBox = new CheckBox(context);
        blackWhiteCheckBox.setText("B&W");
        blackWhiteCheckBox.setTextColor(Color.rgb(243, 245, 247));
        blackWhiteCheckBox.setTextSize(12);
        row.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(blackWhiteCheckBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView fpsLabel = createControlText("FPS request", 12, false);
        controls.addView(fpsLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        fpsSpinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, FPS_ENTRIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);
        controls.addView(fpsSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        controlStatusTextView = createControlText("Brightness/contrast/B&W have display fallback; gain, exposure and FPS depend on UVC support.", 11, false);
        controls.addView(controlStatusTextView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        floatingControlsPanel.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        panelParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        decorContent.addView(floatingControlsPanel, panelParams);
        wireCameraControlActions(title, scrollView);
    }

    private void wireCameraControlActions(TextView title, ScrollView scrollView) {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || isApplyingSavedCameraControls) return;
                readCameraControlWidgets();
                updateCameraControlLabels();
                applyAllCameraControls("slider change");
                saveCameraControlSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                readCameraControlWidgets();
                applyAllCameraControls("slider released");
                saveCameraControlSettings();
            }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(listener);
        contrastSeekBar.setOnSeekBarChangeListener(listener);
        gainSeekBar.setOnSeekBarChangeListener(listener);
        exposureSeekBar.setOnSeekBarChangeListener(listener);

        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isApplyingSavedCameraControls) return;
                readCameraControlWidgets();
                applyAllCameraControls("auto exposure changed");
                saveCameraControlSettings();
            }
        });
        blackWhiteCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isApplyingSavedCameraControls) return;
                readCameraControlWidgets();
                applyAllCameraControls("B&W changed");
                saveCameraControlSettings();
            }
        });
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isApplyingSavedCameraControls) return;
                int oldFps = requestedFps;
                requestedFps = FPS_VALUES[Math.max(0, Math.min(FPS_VALUES.length - 1, position))];
                saveCameraControlSettings();
                if (oldFps != requestedFps) {
                    reopenActiveCameraIfPossible("FPS request changed to " + (requestedFps == 0 ? "default" : requestedFps + " fps"));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        title.setTag(scrollView);
    }

    private SeekBar addCameraSlider(LinearLayout parent, String label, int initial, TextView valueTextView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(createControlText(label, 12, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valueTextView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(initial);
        parent.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return seekBar;
    }

    private void loadCameraControlSettings() {
        SharedPreferences preferences = context.getSharedPreferences(CAMERA_CONTROL_SETTINGS, Context.MODE_PRIVATE);
        brightnessValue = clampInt(preferences.getInt("brightnessValue", 50), 0, 100);
        contrastValue = clampInt(preferences.getInt("contrastValue", 50), 0, 100);
        gainValue = clampInt(preferences.getInt("gainValue", 0), 0, 100);
        exposureValue = clampInt(preferences.getInt("exposureValue", 50), 0, 100);
        autoExposure = preferences.getBoolean("autoExposure", true);
        blackWhite = preferences.getBoolean("blackWhite", false);
        requestedFps = preferences.getInt("requestedFps", 0);
        if (floatingControlsPanel == null) {
            applyPreviewDisplayFilter();
            return;
        }
        isApplyingSavedCameraControls = true;
        brightnessSeekBar.setProgress(brightnessValue);
        contrastSeekBar.setProgress(contrastValue);
        gainSeekBar.setProgress(gainValue);
        exposureSeekBar.setProgress(exposureValue);
        autoExposureCheckBox.setChecked(autoExposure);
        blackWhiteCheckBox.setChecked(blackWhite);
        fpsSpinner.setSelection(fpsToSpinnerIndex(requestedFps));
        isApplyingSavedCameraControls = false;
        updateCameraControlLabels();
        applyPreviewDisplayFilter();
    }

    private void saveCameraControlSettings() {
        context.getSharedPreferences(CAMERA_CONTROL_SETTINGS, Context.MODE_PRIVATE).edit()
                .putInt("brightnessValue", brightnessValue)
                .putInt("contrastValue", contrastValue)
                .putInt("gainValue", gainValue)
                .putInt("exposureValue", exposureValue)
                .putBoolean("autoExposure", autoExposure)
                .putBoolean("blackWhite", blackWhite)
                .putInt("requestedFps", requestedFps)
                .apply();
    }

    private void readCameraControlWidgets() {
        brightnessValue = brightnessSeekBar == null ? brightnessValue : brightnessSeekBar.getProgress();
        contrastValue = contrastSeekBar == null ? contrastValue : contrastSeekBar.getProgress();
        gainValue = gainSeekBar == null ? gainValue : gainSeekBar.getProgress();
        exposureValue = exposureSeekBar == null ? exposureValue : exposureSeekBar.getProgress();
        autoExposure = autoExposureCheckBox != null && autoExposureCheckBox.isChecked();
        blackWhite = blackWhiteCheckBox != null && blackWhiteCheckBox.isChecked();
    }

    private void updateCameraControlLabels() {
        if (brightnessValueTextView != null) brightnessValueTextView.setText(String.format(Locale.US, "%+d display / %d raw", (brightnessValue - 50) * 2, brightnessValue));
        if (contrastValueTextView != null) contrastValueTextView.setText(String.format(Locale.US, "%.2fx / %d raw", Math.max(0.05f, contrastValue / 50.0f), contrastValue));
        if (gainValueTextView != null) gainValueTextView.setText(String.format(Locale.US, "%d raw", gainValue));
        if (exposureValueTextView != null) exposureValueTextView.setText(autoExposure ? String.format(Locale.US, "%d raw; auto", exposureValue) : String.format(Locale.US, "%d raw; manual", exposureValue));
    }

    private void applyAllCameraControls(String reason) {
        applyPreviewDisplayFilter();
        StringBuilder status = new StringBuilder();
        status.append("Controls applied (").append(reason).append("). ");
        if (activeUvcCamera == null) {
            status.append("No UVC camera is currently open; hardware controls will be retried after preview opens.");
            setControlStatus(status.toString());
            return;
        }

        int supported = 0;
        supported += tryInvokeCameraInt(activeUvcCamera, brightnessValue, "setBrightness") ? 1 : 0;
        supported += tryInvokeCameraInt(activeUvcCamera, contrastValue, "setContrast") ? 1 : 0;
        supported += tryInvokeCameraInt(activeUvcCamera, gainValue, "setGain") ? 1 : 0;
        supported += tryInvokeCameraBoolean(activeUvcCamera, autoExposure, "setAutoExposure", "setAutoExposureMode") ? 1 : 0;
        if (!autoExposure) {
            supported += tryInvokeCameraInt(activeUvcCamera, exposureValue, "setExposure", "setExposureTime", "setExposureAbs") ? 1 : 0;
        }
        boolean blackWhiteHardware = applyBlackWhiteRenderEffectIfPossible();
        status.append(supported).append(" UVC hardware command(s) accepted by AUSBC reflection. ");
        status.append(blackWhiteHardware ? "B&W render effect accepted. " : "B&W uses display fallback if hardware/render effect is unavailable. ");
        if (requestedFps > 0) {
            status.append("FPS requires stream reopen; requested ").append(requestedFps).append(" fps if builder supports it.");
        }
        setControlStatus(status.toString());
    }

    private void applyPreviewDisplayFilter() {
        float contrast = Math.max(0.05f, contrastValue / 50.0f);
        float brightnessOffset = (brightnessValue - 50) * 2.55f;
        ColorMatrix colorMatrix = new ColorMatrix();
        if (blackWhite) {
            colorMatrix.setSaturation(0.0f);
        }
        ColorMatrix brightnessContrast = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, brightnessOffset,
                0, contrast, 0, 0, brightnessOffset,
                0, 0, contrast, 0, brightnessOffset,
                0, 0, 0, 1, 0
        });
        colorMatrix.postConcat(brightnessContrast);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        previewView.invalidate();
    }

    private boolean applyBlackWhiteRenderEffectIfPossible() {
        if (activeUvcCamera == null) {
            return false;
        }
        if (!blackWhite) {
            return removeBlackWhiteRenderEffectIfNeeded();
        }
        if (activeBlackWhiteEffect == null) {
            activeBlackWhiteEffect = createBlackWhiteEffectInstance();
        }
        if (activeBlackWhiteEffect == null) {
            return false;
        }
        return invokeSingleArgumentMethod(activeUvcCamera, "addRenderEffect", activeBlackWhiteEffect)
                || invokeSingleArgumentMethod(activeUvcCamera, "updateRenderEffect", activeBlackWhiteEffect);
    }

    private boolean removeBlackWhiteRenderEffectIfNeeded() {
        if (activeUvcCamera == null || activeBlackWhiteEffect == null) {
            activeBlackWhiteEffect = null;
            return false;
        }
        boolean removed = invokeSingleArgumentMethod(activeUvcCamera, "removeRenderEffect", activeBlackWhiteEffect);
        activeBlackWhiteEffect = null;
        return removed;
    }

    private Object createBlackWhiteEffectInstance() {
        String[] classNames = {
                "com.jiangdg.ausbc.render.effect.EffectBlackWhite",
                "com.jiangdg.ausbc.render.effect.EffectBlackWhiteKt",
                "com.jiangdg.ausbc.render.effect.BlackWhiteEffect"
        };
        for (String className : classNames) {
            try {
                Class<?> effectClass = Class.forName(className);
                for (Constructor<?> constructor : effectClass.getConstructors()) {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(Context.class)) {
                        return constructor.newInstance(context);
                    }
                    if (parameterTypes.length == 0) {
                        return constructor.newInstance();
                    }
                }
            } catch (Throwable ignored) {
                // Try the next possible class name.
            }
        }
        return null;
    }

    private boolean tryInvokeCameraInt(Object camera, int value, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = camera.getClass().getMethod(methodName, int.class);
                method.invoke(camera, value);
                return true;
            } catch (Throwable ignored) {
                // Try alternate names below.
            }
        }
        return false;
    }

    private boolean tryInvokeCameraBoolean(Object camera, boolean value, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = camera.getClass().getMethod(methodName, boolean.class);
                method.invoke(camera, value);
                return true;
            } catch (Throwable ignored) {
                // Try alternate names below.
            }
        }
        return false;
    }

    private boolean tryInvokeBuilderInt(Object builder, int value, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = builder.getClass().getMethod(methodName, int.class);
                method.invoke(builder, value);
                return true;
            } catch (Throwable ignored) {
                // Try alternate names below.
            }
        }
        return false;
    }

    private boolean invokeSingleArgumentMethod(Object target, String methodName, Object argument) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(argument.getClass())) {
                    method.invoke(target, argument);
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Treat unsupported render-effect paths as non-fatal.
        }
        return false;
    }

    private void reopenActiveCameraIfPossible(String reason) {
        if (activeUvcCamera == null || pendingOpenDevice == null) {
            setControlStatus(reason + "; no open camera to restart yet.");
            return;
        }
        try {
            removeBlackWhiteRenderEffectIfNeeded();
            activeUvcCamera.closeCamera();
            openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
            setControlStatus(reason + "; preview reopened with the new request if AUSBC supports the FPS method.");
        } catch (Throwable throwable) {
            setControlStatus("Could not reopen preview after " + reason + ": " + describeThrowable(throwable));
        }
    }

    private void updateControlsPanelCollapsedState() {
        if (floatingControlsPanel == null) {
            return;
        }
        for (int index = 1; index < floatingControlsPanel.getChildCount(); index++) {
            floatingControlsPanel.getChildAt(index).setVisibility(controlsPanelCollapsed ? View.GONE : View.VISIBLE);
        }
        TextView title = (TextView) floatingControlsPanel.getChildAt(0);
        title.setText(controlsPanelCollapsed ? "Camera image controls  ▼" : "Camera image controls  ▲");
    }

    private void removeFloatingCameraControls() {
        if (floatingControlsPanel == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) floatingControlsPanel.getParent();
        if (parent != null) {
            parent.removeView(floatingControlsPanel);
        }
        floatingControlsPanel = null;
    }

    private void setControlStatus(String text) {
        if (controlStatusTextView != null) {
            controlStatusTextView.setText(text == null ? "" : text);
        }
        if (text != null && !text.trim().isEmpty()) {
            notifyStatus(text);
        }
    }

    private TextView createControlText(String text, int sizeSp, boolean bold) {
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

    private TextView createControlValueText() {
        TextView textView = createControlText("—", 11, false);
        textView.setGravity(Gravity.END);
        return textView;
    }

    private int fpsToSpinnerIndex(int fps) {
        for (int index = 0; index < FPS_VALUES.length; index++) {
            if (FPS_VALUES[index] == fps) {
                return index;
            }
        }
        return 0;
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

    private String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "no detail message";
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private void notifyStatus(String statusText) {
        if (listener != null) {
            listener.onUvcStatusChanged(statusText);
        }
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
