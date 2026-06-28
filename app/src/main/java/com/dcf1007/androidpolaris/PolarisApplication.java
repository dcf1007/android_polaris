package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import com.dcf1007.androidpolaris.camera.MainInterfaceOrganizer;

/**
 * Application-level UI hook.
 *
 * <p>MainActivity is a large programmatic-layout file. To avoid another risky full-file rewrite,
 * this hook applies the static UI organization immediately after MainActivity has installed its
 * content view. The organizer is idempotent: it can safely run again after resume or after the UVC
 * options panel is created.</p>
 */
public final class PolarisApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                organizeIfMainActivity(activity);
            }

            @Override public void onActivityResumed(Activity activity) {
                organizeIfMainActivity(activity);
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void organizeIfMainActivity(final Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        final View contentRoot = activity.findViewById(android.R.id.content);
        if (contentRoot == null) return;
        contentRoot.post(new Runnable() {
            @Override public void run() {
                MainInterfaceOrganizer.organize(contentRoot);
            }
        });
    }
}
