package com.dcf1007.androidpolaris.camera;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB-host monitor for OTG UVC devices.
 *
 * Android has two distinct paths for USB cameras:
 *
 * 1. Camera2 external camera path: the device/vendor exposes the UVC camera as
 *    a Camera2 camera ID. The app can preview it through Camera2.
 * 2. Raw USB-host path: Android sees a USB Video Class device, but it is not
 *    exposed through Camera2. This monitor detects that device and requests USB
 *    permission. Actual frame streaming through raw UVC endpoints requires a
 *    UVC stream driver/native library, which is intentionally not bundled here
 *    to keep the project dependency-free and easy to audit.
 */
public final class UsbUvcDeviceMonitor {
    public interface Listener {
        void onUsbStatus(String statusText);
    }

    private static final String ACTION_USB_PERMISSION = "com.dcf1007.androidpolaris.USB_PERMISSION";
    private static final int USB_VIDEO_CLASS = 14;

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private final BroadcastReceiver usbReceiver;

    public UsbUvcDeviceMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                handleUsbBroadcast(intent);
            }
        };
    }

    public void register() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    public void unregister() {
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already unregistered. This can happen during activity teardown.
        }
    }

    public List<UsbDevice> listConnectedUvcDevices() {
        List<UsbDevice> uvcDevices = new ArrayList<>();
        if (usbManager == null) {
            return uvcDevices;
        }
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isUsbVideoClassDevice(device)) {
                uvcDevices.add(device);
            }
        }
        return uvcDevices;
    }

    public String describeConnectedUvcDevices() {
        List<UsbDevice> devices = listConnectedUvcDevices();
        if (devices.isEmpty()) {
            return "No raw USB Video Class devices detected.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(devices.size()).append(" raw UVC device(s) detected:");
        for (UsbDevice device : devices) {
            builder.append('\n').append(describeDevice(device));
        }
        builder.append("\nIf a detected UVC camera also appears as a Camera2 external camera, select that Camera2 entry for preview.");
        return builder.toString();
    }

    public void requestPermissionForFirstUvcDevice() {
        List<UsbDevice> devices = listConnectedUvcDevices();
        if (devices.isEmpty()) {
            publish("No UVC device available for USB permission request.");
            return;
        }
        UsbDevice device = devices.get(0);
        if (usbManager == null) {
            publish("USB host manager is not available on this device.");
            return;
        }
        if (usbManager.hasPermission(device)) {
            publish("USB permission already granted for " + describeDevice(device));
            return;
        }

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                device.getDeviceId(),
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        usbManager.requestPermission(device, pendingIntent);
        publish("Requested USB permission for " + describeDevice(device));
    }

    private void handleUsbBroadcast(Intent intent) {
        String action = intent.getAction();
        UsbDevice device;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            @SuppressWarnings("deprecation")
            UsbDevice legacyDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            device = legacyDevice;
        }

        if (ACTION_USB_PERMISSION.equals(action)) {
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            publish((granted ? "USB permission granted for " : "USB permission denied for ") + describeDevice(device));
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            if (device != null && isUsbVideoClassDevice(device)) {
                publish("UVC device attached: " + describeDevice(device));
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            if (device != null && isUsbVideoClassDevice(device)) {
                publish("UVC device detached: " + describeDevice(device));
            }
        }
    }

    private static boolean isUsbVideoClassDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == USB_VIDEO_CLASS) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) {
                return true;
            }
        }
        return false;
    }

    private static String describeDevice(UsbDevice device) {
        if (device == null) {
            return "unknown USB device";
        }
        return String.format(
                Locale.US,
                "VID %04x / PID %04x, interfaces=%d, name=%s",
                device.getVendorId(),
                device.getProductId(),
                device.getInterfaceCount(),
                device.getDeviceName()
        );
    }

    private void publish(String statusText) {
        if (listener != null) {
            listener.onUsbStatus(statusText);
        }
    }
}
