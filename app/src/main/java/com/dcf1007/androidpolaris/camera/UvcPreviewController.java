package com.dcf1007.androidpolaris.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.view.TextureView;

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
 * <p>This class intentionally contains no Camera2 code. The target hardware path is:</p>
 *
 * <pre>
 * USB OTG camera -> Android USB Host permission -> AUSBC/libuvc backend -> TextureView preview
 * </pre>
 *
 * <p>The controller owns only the USB/UVC lifecycle. MainActivity owns Android runtime
 * permissions and the UI. The astronomy/reticle overlay remains independent of the camera backend.</p>
 *
 * <p>The code is written against the pinned AUSBC 3.2.7 API. That release exposes UVC
 * camera instances as {@link MultiCameraClient.Camera}, uses Serenegiant's
 * {@link USBMonitor.UsbControlBlock} type, and supports a compact {@link CameraRequest}
 * with preview size and AF/AE settings.</p>
 */
public final class UvcPreviewController {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int DEFAULT_PREVIEW_WIDTH = 1280;
    private static final int DEFAULT_PREVIEW_HEIGHT = 720;

    /** Receives status messages that should be displayed in the Activity. */
    public interface Listener {
        void onUvcStatusChanged(String statusText);
    }

    private final Context context;
    private final AspectRatioTextureView previewView;
    private final Listener listener;
    private final MultiCameraClient uvcClient;

    private final Map<Integer, UsbDevice> detectedUvcDevicesById = new LinkedHashMap<>();
    private final Map<Integer, MultiCameraClient.Camera> uvcCamerasByDeviceId = new LinkedHashMap<>();

    private boolean isUsbMonitorRegistered;
    private boolean isPreviewSurfaceAvailable;
    private UsbDevice pendingOpenDevice;
    private MultiCameraClient.Camera activeUvcCamera;

    public UvcPreviewController(Context context, AspectRatioTextureView previewView, Listener listener) {
        this.context = context;
        this.previewView = previewView;
        this.listener = listener;
        this.uvcClient = new MultiCameraClient(context, createDeviceConnectionCallback());
        configurePreviewSurfaceCallbacks();
    }

    /** Starts listening for USB attach/detach events. Safe to call repeatedly. */
    public void register() {
        if (isUsbMonitorRegistered) {
            return;
        }
        uvcClient.register();
        isUsbMonitorRegistered = true;
        notifyStatus("UVC monitor registered. Connect the USB OTG camera, then tap Open USB UVC camera.");
        refreshDetectedDeviceCacheFromUsbHost();
    }

    /** Stops listening for USB events and closes the active preview if needed. */
    public void unregister() {
        closeActiveCamera();
        if (!isUsbMonitorRegistered) {
            return;
        }
        uvcClient.unRegister();
        isUsbMonitorRegistered = false;
        notifyStatus("UVC monitor unregistered.");
    }

    /** Releases the AUSBC USB monitor. Call from Activity.onDestroy(). */
    public void destroy() {
        closeActiveCamera();
        uvcCamerasByDeviceId.clear();
        detectedUvcDevicesById.clear();
        uvcClient.destroy();
    }

    /**
     * Requests Android USB permission for the first detected UVC device.
     *
     * <p>The request is asynchronous. If the user grants permission, AUSBC calls
     * {@code onConnectDev()}, where the preview is opened.</p>
     */
    public void requestPermissionAndOpenFirstCamera() {
        if (!isUsbMonitorRegistered) {
            register();
        }

        refreshDetectedDeviceCacheFromUsbHost();
        UsbDevice firstDevice = getFirstDetectedUvcDevice();
        if (firstDevice == null) {
            notifyStatus("No raw USB UVC camera detected. Check OTG power/cable and reconnect the camera.");
            return;
        }

        pendingOpenDevice = firstDevice;
        notifyStatus("Requesting USB permission for " + describeDeviceBrief(firstDevice) + "…");
        boolean requestStarted = uvcClient.requestPermission(firstDevice);
        if (!requestStarted) {
            notifyStatus("USB permission request could not start. Reconnect the camera and try again.");
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
            } catch (RuntimeException exception) {
                notifyStatus("UVC close warning: " + exception.getMessage());
            }
            activeUvcCamera = null;
        }
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
                    removedCamera.closeCamera();
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
                rememberDetectedDevice(device);
                pendingOpenDevice = device;
                MultiCameraClient.Camera camera = getOrCreateUvcCamera(device);
                camera.setUsbControlBlock(controlBlock);
                camera.setCameraStateCallBack(createCameraStateCallback());
                activeUvcCamera = camera;
                openCameraWhenPreviewSurfaceIsReady(camera, device);
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
                if (activeUvcCamera != null && pendingOpenDevice != null) {
                    openCameraWhenPreviewSurfaceIsReady(activeUvcCamera, pendingOpenDevice);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                // AUSBC handles the actual UVC stream. The overlay is resized independently by Android layout.
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
        if (!isPreviewSurfaceAvailable) {
            notifyStatus("UVC permission granted for " + describeDeviceBrief(device) + ", waiting for preview surface…");
            return;
        }

        try {
            camera.openCamera(previewView, createPreviewRequest());
            notifyStatus("Opening UVC preview for " + describeDeviceBrief(device) + "…");
        } catch (RuntimeException exception) {
            notifyStatus("Failed to open UVC preview: " + exception.getMessage());
        }
    }

    /**
     * Creates the minimal AUSBC 3.2.7 request needed for preview.
     *
     * <p>Newer AUSBC versions expose render/audio/format options on this builder. Version 3.2.7
     * does not, so the native backend internally tries MJPEG and falls back to YUYV where needed.</p>
     */
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
        List<UsbDevice> devices = getUvcDevicesFromClient();
        for (UsbDevice device : devices) {
            rememberDetectedDevice(device);
        }
    }

    private List<UsbDevice> getUvcDevicesFromClient() {
        List<UsbDevice> filteredDevices = new ArrayList<>();
        List<UsbDevice> allUsbDevices = uvcClient.getDeviceList(null);
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
        if (device == null) {
            return;
        }
        detectedUvcDevicesById.put(device.getDeviceId(), device);
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

    private void notifyStatus(String statusText) {
        if (listener != null) {
            listener.onUvcStatusChanged(statusText);
        }
    }
}
