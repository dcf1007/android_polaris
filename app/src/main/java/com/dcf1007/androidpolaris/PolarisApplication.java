package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.dcf1007.androidpolaris.camera.MainInterfaceOrganizer;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Startup coordinator for the single-activity app.
 *
 * <p>MainActivity owns the UI and UVC controller. This class normalizes the menu, makes the UVC
 * stream controls available immediately at app load, and triggers the same camera-query path
 * automatically when Android reports a raw USB device at app start or attach. It does not require a
 * visible manual Open/query button.</p>
 */
public final class PolarisApplication extends Application {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int AUTO_QUERY_MARKER_KEY = 0x706f6c61; // "pola"
    private static final int COLOR_STREAM_BUTTON = Color.rgb(134, 183, 255);
    private static final int COLOR_STREAM_BUTTON_TEXT = Color.rgb(6, 16, 31);
    private static final int COLOR_DISABLED_BUTTON = Color.rgb(58, 63, 73);
    private static final int COLOR_DISABLED_BUTTON_TEXT = Color.rgb(154, 163, 176);

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
                ensureUvcController(activity);
                MainInterfaceOrganizer.organize(contentRoot);
                sanitizeVisibleStreamControls(contentRoot);
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

    /** Initializes the UVC controller/panel without requesting USB permission or starting preview. */
    private static void ensureUvcController(Activity activity) {
        try {
            Method ensureMethod = MainActivity.class.getDeclaredMethod("ensureUvcPreviewController");
            ensureMethod.setAccessible(true);
            ensureMethod.invoke(activity);
        } catch (Throwable throwable) {
            reportToMainActivity(activity, "UVC controls failed to initialize: " + throwable.getClass().getSimpleName() + ".");
        }
    }

    /**
     * Sanitizes legacy stream controls without removing any layout container. The previous version
     * removed a parent when any descendant matched, which could blank the screen. This version only
     * removes an exact legacy Button leaf and otherwise recurses into child groups.
     */
    private static void sanitizeVisibleStreamControls(View root) {
        removeExactButtonLeaves(root, "Stop camera");
        renameExactButtonText(root, "Start selected stream", "Start stream");
        styleStreamButtons(root);
    }

    private static void removeExactButtonLeaves(View view, String text) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            if (child instanceof Button && text.contentEquals(((Button) child).getText())) {
                group.removeViewAt(index);
            } else {
                removeExactButtonLeaves(child, text);
            }
        }
    }

    private static void renameExactButtonText(View view, String from, String to) {
        if (view instanceof Button && from.contentEquals(((Button) view).getText())) {
            ((Button) view).setText(to);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) renameExactButtonText(group.getChildAt(index), from, to);
    }

    private static void styleStreamButtons(View view) {
        if (view instanceof Button) {
            Button button = (Button) view;
            CharSequence text = button.getText();
            if ("Refresh USB devices".contentEquals(text)
                    || "Start stream".contentEquals(text)
                    || "Stop stream".contentEquals(text)) {
                applyPrimaryStreamButtonStyle(button);
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) styleStreamButtons(group.getChildAt(index));
    }

    private static void applyPrimaryStreamButtonStyle(Button button) {
        boolean enabled = button.isEnabled();
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(enabled ? COLOR_STREAM_BUTTON_TEXT : COLOR_DISABLED_BUTTON_TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(button, 42));
        button.setPadding(dp(button, 10), dp(button, 8), dp(button, 10), dp(button, 8));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(enabled ? COLOR_STREAM_BUTTON : COLOR_DISABLED_BUTTON);
        background.setCornerRadius(dp(button, 10));
        button.setBackground(background);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getContext().getResources().getDisplayMetrics().density);
    }

    private static void triggerAutomaticCameraQuery(Activity activity) {
        try {
            Method requestMethod = MainActivity.class.getDeclaredMethod("requestCameraPermissionThenOpenUvc");
            requestMethod.setAccessible(true);
            requestMethod.invoke(activity);
        } catch (Throwable throwable) {
            reportToMainActivity(activity, "Automatic UVC query failed to start: " + throwable.getClass().getSimpleName() + ".");
        }
    }

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
