package com.dcf1007.androidpolaris.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.serenegiant.usb.USBMonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USB OTG / UVC-only preview controller.
 *
 * <p>This class is the only active code path that touches AUSBC/libuvc. MainActivity creates it
 * lazily only after the user presses the USB-camera button. That design is intentional: if the
 * third-party UVC backend fails on a particular Android build, the app can still launch, display
 * diagnostics, and run the Polaris calculation instead of closing immediately.</p>
 *
 * <p>Target flow:</p>
 *
 * <pre>
 * USB OTG UVC camera
 *   -> Android USB Host enumeration
 *   -> Android USB permission request
 *   -> AUSBC/libuvc native camera backend
 *   -> AspectRatioTextureView preview
 *   -> native Android reticle overlay drawn by MainActivity
 * </pre>
 *
 * <p>The code is written against the pinned AUSBC 3.2.7 API. That release exposes UVC camera
 * instances as {@link MultiCameraClient.Camera}, uses Serenegiant's
 * {@link USBMonitor.UsbControlBlock} type, and supports a compact {@link CameraRequest} builder.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int DEFAULT_PREVIEW_WIDTH = 1280;
    private static final int DEFAULT_PREVIEW_HEIGHT = 720;
    private static final long SURFACE_RECHECK_DELAY_MS = 250L;

    /** Receives status messages that should be displayed in the Activity. */
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

    public UvcPreviewController(Context context, FrameLayout previewContainer, Listener listener) {
        this.context = context;
        this.previewContainer = previewContainer;
        this.listener = listener;

        // This view is part of AUSBC. It is created only after the user asks to open UVC preview,
        // never during app startup. Keeping it here avoids loading AUSBC from MainActivity.onCreate().
        this.previewView = new AspectRatioTextureView(context);
        configurePreviewSurfaceCallbacks();

        this.previewContainer.removeAllViews();
        this.previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        this.previewContainer.setVisibility(View.VISIBLE);
        this.previewContainer.requestLayout();
        this.previewView.requestLayout();

        this.uvcClient = new MultiCameraClient(context, createDeviceConnectionCallback());

        // If the TextureView surface becomes available between construction and the first camera
        // connection callback, the listener will not necessarily be called again. Re-check after
        // the view has gone through layout so the open path cannot stall forever on a stale flag.
        scheduleSurfaceAvailabilityRecheck("initial preview view attach");
    }

    /**
     * Starts listening for USB attach/detach events.
     *
     * <p>All AUSBC calls are guarded. If an Android-version/library mismatch occurs, report it to
     * the UI instead of letting the exception terminate the Activity.</p>
     */
    public boolean register() {
        if (isUsbMonitorRegistered) {
            return true;
        }
        try {
            uvcClient.register();
            isUsbMonitorRegistered = true;
            refreshDetectedDeviceCacheFromUsbHost();
            notifyStatus("UVC monitor registered. Connect the USB OTG camera, then tap Open USB UVC camera if permission is not requested automatically.");
            return true;
        } catch (Throwable throwable) {
            isUsbMonitorRegistered = false;
            notifyStatus("UVC monitor registration failed: " + describeThrowable(throwable));
            return false;
        }
    }

    /** Stops listening for USB events and closes the active preview if needed. */
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

    /** Releases the AUSBC USB monitor. Call from Activity.onDestroy(). */
    public void destroy() {
        closeActiveCamera();
        uvcCamerasByDeviceId.clear();
        detectedUvcDevicesById.clear();
        try {
            uvcClient.destroy();
        } catch (Throwable throwable) {
            notifyStatus("UVC destroy warning: " + describeThrowable(throwable));
        }
        previewContainer.removeAllViews();
    }

    /**
     * Requests Android USB permission for the first detected UVC device.
     *
     * <p>The request is asynchronous. If the user grants permission, AUSBC calls
     * {@code onConnectDev()}, where the preview is opened.</p>
     */
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

    /** Returns a human-readable list of currently detected UVC devices. */
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
        builder.append("Preview source: USB OTG UVC backend only. Camera2 is not used.");
        return builder.toString().trim();
    }

    /** Closes the active UVC preview and releases the current native camera object. */
    public void closeActiveCamera() {
        if (activeUvcCamera != null) {
            try {
                activeUvcCamera.closeCamera();
            } catch (Throwable throwable) {
                notifyStatus("UVC close warning: " + describeThrowable(throwable));
            }
            activeUvcCamera = null;
        }
        isOpenAttemptScheduled = false;
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
                    notifyStatus("UVC preview opened. Use the pink Polaris target relative to the reticle NCP/crosshair.");
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
                notifyStatus("Preview surface ready: " + width + "×" + height + ".");
                if (activeUvcCamera != null && pendingOpenDevice != null) {
                    openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                // AUSBC handles the stream surface. The reticle overlay is resized independently.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                isPreviewSurfaceAvailable = false;
                closeActiveCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                // No per-frame processing is required; the reticle is drawn in a separate overlay View.
            }
        });
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

    /** Creates the minimal AUSBC 3.2.7 request needed for preview. */
    private CameraRequest createPreviewRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(DEFAULT_PREVIEW_WIDTH)
                .setPreviewHeight(DEFAULT_PREVIEW_HEIGHT)
                .setContinuousAFModel(true)
                .setContinuousAutoModel(true)
                .create();
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
        return String.format(Locale.US,
                "VID %04x / PID %04x",
                device.getVendorId(),
                device.getProductId());
    }

    private String describeDeviceLong(UsbDevice device) {
        return String.format(Locale.US,
                "VID %04x / PID %04x, interfaces=%d, name=%s",
                device.getVendorId(),
                device.getProductId(),
                device.getInterfaceCount(),
                device.getDeviceName());
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
}
