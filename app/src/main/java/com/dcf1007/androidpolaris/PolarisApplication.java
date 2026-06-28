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

/**
 * Startup coordinator for the single-activity app.
 *
 * <p>MainActivity owns the visible UI and UVC controller. This class only normalizes the visible
 * menu and triggers the normal Open/query button when Android reports USB attach/open events.
 * It does not inject raw USB devices into the UVC backend.</p>
 */
public final class PolarisApplication extends Application {
    private static final int AUTO_QUERY_MARKER_KEY = 0x706f6c61; // "pola"

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
                    View openQueryButton = findTextViewWithText(contentRoot, "Open/query USB UVC camera");
                    if (openQueryButton != null) openQueryButton.performClick();
                }
            }
        });
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
