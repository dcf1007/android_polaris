package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.app.Application;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.dcf1007.androidpolaris.camera.MainInterfaceOrganizer;

/**
 * Application-level launch hook for static UI organization and camera auto-query.
 *
 * <p>The real camera lifecycle still lives in MainActivity and UvcPreviewController. This hook only
 * runs after MainActivity has built its view: it organizes the visible controls and, when Android
 * USB Host already reports a UVC camera, presses the same Open/query button the user would press.</p>
 */
public final class PolarisApplication extends Application {
    private static final int USB_VIDEO_CLASS = 14;
    private static final int AUTO_QUERY_MARKER_KEY = 0x706f6c61; // "pola"

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { organizeAndMaybeQuery(activity); }
            @Override public void onActivityResumed(Activity activity) { organizeAndMaybeQuery(activity); }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void organizeAndMaybeQuery(final Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        final View contentRoot = activity.findViewById(android.R.id.content);
        if (contentRoot == null) return;
        contentRoot.post(new Runnable() {
            @Override public void run() {
                MainInterfaceOrganizer.organize(contentRoot);
                if (!Boolean.TRUE.equals(contentRoot.getTag(AUTO_QUERY_MARKER_KEY)) && hasAttachedUvcDevice(activity)) {
                    View openQueryButton = findTextViewWithText(contentRoot, "Open/query USB UVC camera");
                    if (openQueryButton != null) {
                        contentRoot.setTag(AUTO_QUERY_MARKER_KEY, Boolean.TRUE);
                        openQueryButton.performClick();
                    }
                }
            }
        });
    }

    private static boolean hasAttachedUvcDevice(Activity activity) {
        UsbManager usbManager = (UsbManager) activity.getSystemService(USB_SERVICE);
        if (usbManager == null) return false;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = device.getInterface(i);
                if (usbInterface != null && usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) return true;
            }
        }
        return false;
    }

    private static View findTextViewWithText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTextViewWithText(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
