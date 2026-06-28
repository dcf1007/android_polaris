package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.dcf1007.androidpolaris.camera.MainInterfaceOrganizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Startup coordinator for the single-activity app.
 *
 * <p>MainActivity owns the visible UI and UVC controller. This class handles app-level USB attach
 * events and bridges Android's raw USB-device list into the UVC controller when a phone reports
 * attach/detach events but does not expose interface class 14 before USB permission is requested.</p>
 */
public final class PolarisApplication extends Application {
    private static final int AUTO_QUERY_MARKER_KEY = 0x706f6c61; // "pola"
    private static final long QUERY_RETRY_DELAY_MS = 700L;

    private Activity currentMainActivity;

    @Override public void onCreate() {
        super.onCreate();
        registerUsbAttachReceiver();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                if (activity instanceof MainActivity) currentMainActivity = activity;
                organizeAndMaybeQuery(activity, false);
            }
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity) currentMainActivity = activity;
                organizeAndMaybeQuery(activity, false);
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

    private void registerUsbAttachReceiver() {
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;
                Activity activity = currentMainActivity;
                if (activity != null) organizeAndMaybeQuery(activity, true);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private static void organizeAndMaybeQuery(final Activity activity, final boolean forceQuery) {
        if (!(activity instanceof MainActivity)) return;
        final View contentRoot = activity.findViewById(android.R.id.content);
        if (contentRoot == null) return;
        contentRoot.post(new Runnable() {
            @Override public void run() {
                MainInterfaceOrganizer.organize(contentRoot);
                boolean alreadyQueried = Boolean.TRUE.equals(contentRoot.getTag(AUTO_QUERY_MARKER_KEY));
                if ((forceQuery || !alreadyQueried) && hasAttachedUsbDevice(activity)) {
                    contentRoot.setTag(AUTO_QUERY_MARKER_KEY, Boolean.TRUE);
                    triggerNormalOpenQueryPath(activity, contentRoot);
                }
            }
        });
    }

    private static void triggerNormalOpenQueryPath(final Activity activity, final View contentRoot) {
        View openQueryButton = findTextViewWithText(contentRoot, "Open/query USB UVC camera");
        if (openQueryButton != null) openQueryButton.performClick();
        contentRoot.postDelayed(new Runnable() {
            @Override public void run() { injectRawUsbDevicesAndRetryQuery(activity, contentRoot); }
        }, QUERY_RETRY_DELAY_MS);
        contentRoot.postDelayed(new Runnable() {
            @Override public void run() { injectRawUsbDevicesAndRetryQuery(activity, contentRoot); }
        }, QUERY_RETRY_DELAY_MS * 2L);
    }

    /**
     * Fallback for devices/phones where UsbManager lists a device but its UVC interface is not
     * visible before permission. The controller still performs the real libuvc open/query.
     */
    @SuppressWarnings("unchecked")
    private static void injectRawUsbDevicesAndRetryQuery(Activity activity, View contentRoot) {
        try {
            UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
            if (usbManager == null || usbManager.getDeviceList().isEmpty()) return;

            Field controllerField = MainActivity.class.getDeclaredField("uvcPreviewController");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(activity);
            if (controller == null) {
                View openQueryButton = findTextViewWithText(contentRoot, "Open/query USB UVC camera");
                if (openQueryButton != null) openQueryButton.performClick();
                return;
            }

            Field detectedField = controller.getClass().getDeclaredField("detectedUvcDevicesById");
            detectedField.setAccessible(true);
            Map<Integer, UsbDevice> detected = (Map<Integer, UsbDevice>) detectedField.get(controller);
            for (UsbDevice device : usbManager.getDeviceList().values()) detected.put(device.getDeviceId(), device);

            Method requestMethod = controller.getClass().getMethod("requestPermissionAndOpenFirstCamera");
            requestMethod.invoke(controller);
        } catch (Throwable ignored) {
            // The normal visible Open/query path remains available if this compatibility bridge fails.
        }
    }

    private static boolean hasAttachedUsbDevice(Activity activity) {
        UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        return usbManager != null && !usbManager.getDeviceList().isEmpty();
    }

    private static View findTextViewWithText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = findTextViewWithText(group.getChildAt(index), text);
            if (found != null) return found;
        }
        return null;
    }
}
