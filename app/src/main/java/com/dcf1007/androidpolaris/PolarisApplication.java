package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import com.dcf1007.androidpolaris.camera.MainInterfaceOrganizer;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Application-level UVC coordinator.
 *
 * <p>This class intentionally does only three jobs:</p>
 * <ol>
 *     <li>Run the one-pass UI organizer after MainActivity builds its programmatic layout.</li>
 *     <li>Create the UVC controller/panel at app load so Refresh/Start buttons are always visible.</li>
 *     <li>Trigger the normal MainActivity camera-query path when Android reports a raw USB attach.</li>
 * </ol>
 *
 * <p>Button styling and button state are now owned by the UVC panel itself. Keeping that responsibility
 * out of this lifecycle callback avoids repeated tree-wide styling passes on resume.</p>
 */
public final class PolarisApplication extends Application {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int AUTO_QUERY_MARKER_KEY = 0x706f6c61; // "pola"

    private Activity currentMainActivity;

    @Override public void onCreate() {
        super.onCreate();
        registerUsbAttachReceiver();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                if (activity instanceof MainActivity) currentMainActivity = activity;
                initializeUiAndMaybeQuery(activity, false);
            }
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity) currentMainActivity = activity;
                initializeUiAndMaybeQuery(activity, false);
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) {
                if (activity == currentMainActivity) currentMainActivity = null;
            }
        });
    }

    /** Receives USB attach events while the app is already running. */
    private void registerUsbAttachReceiver() {
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;
                Activity activity = currentMainActivity;
                if (activity != null) initializeUiAndMaybeQuery(activity, true);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /**
     * Runs after MainActivity has a content view. The posted runnable lets the activity finish layout
     * creation first, then safely adds/updates the UVC controls.
     */
    private static void initializeUiAndMaybeQuery(final Activity activity, final boolean forceQuery) {
        if (!(activity instanceof MainActivity)) return;
        final View contentRoot = activity.findViewById(android.R.id.content);
        if (contentRoot == null) return;
        contentRoot.post(new Runnable() {
            @Override public void run() {
                ensureUvcController(activity);
                MainInterfaceOrganizer.organize(contentRoot);

                UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
                if (usbManager == null || usbManager.getDeviceList().isEmpty()) return;

                boolean alreadyQueried = Boolean.TRUE.equals(contentRoot.getTag(AUTO_QUERY_MARKER_KEY));
                if (forceQuery || !alreadyQueried) {
                    contentRoot.setTag(AUTO_QUERY_MARKER_KEY, Boolean.TRUE);
                    reportToMainActivity(activity, "Raw USB device detected. Passing candidate to libuvc query path.\n"
                            + describeRawUsbDevices(usbManager));
                    triggerAutomaticCameraQuery(activity);
                }
            }
        });
    }

    /** Initializes the UVC panel/controller without requesting Android USB permission. */
    private static void ensureUvcController(Activity activity) {
        try {
            Method ensureMethod = MainActivity.class.getDeclaredMethod("ensureUvcPreviewController");
            ensureMethod.setAccessible(true);
            ensureMethod.invoke(activity);
        } catch (Throwable throwable) {
            reportToMainActivity(activity, "UVC controls failed to initialize: " + throwable.getClass().getSimpleName() + ".");
        }
    }

    /** Calls MainActivity's existing permission/query flow instead of duplicating camera logic here. */
    private static void triggerAutomaticCameraQuery(Activity activity) {
        try {
            Method requestMethod = MainActivity.class.getDeclaredMethod("requestCameraPermissionThenOpenUvc");
            requestMethod.setAccessible(true);
            requestMethod.invoke(activity);
        } catch (Throwable throwable) {
            reportToMainActivity(activity, "Automatic UVC query failed to start: " + throwable.getClass().getSimpleName() + ".");
        }
    }

    /** Raw Android descriptor dump for debugging devices whose Java-side name/interfaces are null/zero. */
    private static String describeRawUsbDevices(UsbManager usbManager) {
        StringBuilder builder = new StringBuilder("Android raw USB devices:");
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            builder.append('\n').append(describeDeviceBrief(device))
                    .append(", deviceClass=").append(device.getDeviceClass())
                    .append('/').append(device.getDeviceSubclass())
                    .append('/').append(device.getDeviceProtocol())
                    .append(", interfaces=").append(device.getInterfaceCount())
                    .append(", permission=").append(usbManager.hasPermission(device))
                    .append(", name=").append(device.getDeviceName());
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface usbInterface = device.getInterface(index);
                if (usbInterface == null) continue;
                builder.append('\n')
                        .append("  interface ").append(index)
                        .append(": class=").append(usbInterface.getInterfaceClass())
                        .append('/').append(usbInterface.getInterfaceSubclass())
                        .append('/').append(usbInterface.getInterfaceProtocol())
                        .append(usbInterface.getInterfaceClass() == USB_VIDEO_CLASS ? " USB_VIDEO" : "");
            }
        }
        return builder.toString();
    }

    private static String describeDeviceBrief(UsbDevice device) {
        return String.format(Locale.US, "VID %04x / PID %04x", device.getVendorId(), device.getProductId());
    }

    /** Reports through MainActivity's existing UI/debug log hooks without exposing app logic here. */
    private static void reportToMainActivity(Activity activity, String message) {
        try {
            Method setUvcStatus = MainActivity.class.getDeclaredMethod("setUvcStatus", String.class);
            setUvcStatus.setAccessible(true);
            setUvcStatus.invoke(activity, message);
        } catch (Throwable ignored) { }
        try {
            Method appendDebugLog = MainActivity.class.getDeclaredMethod("appendDebugLog", String.class);
            appendDebugLog.setAccessible(true);
            appendDebugLog.invoke(activity, message);
        } catch (Throwable ignored) { }
    }
}
