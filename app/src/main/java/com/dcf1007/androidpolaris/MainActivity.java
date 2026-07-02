package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.os.Bundle;

import com.dcf1007.androidpolaris.backend.CameraHardwareBackend;
import com.dcf1007.androidpolaris.backend.PolarisAlignmentBackend;
import com.dcf1007.androidpolaris.backend.VideoAlignmentBackend;
import com.dcf1007.androidpolaris.ui.MainScreenView;
import com.dcf1007.androidpolaris.ui.MainUiController;

/**
 * Android composition root for the app.
 *
 * <p>MainActivity owns Android lifecycle and permission callbacks. It creates the screen UI, creates
 * the backend objects, and wires them through MainUiController. UI construction, UI state mutation,
 * USB/libuvc execution, overlay state, and Polaris calculations live in separate files.</p>
 */
public final class MainActivity extends Activity {
    private MainScreenView screenView;
    private CameraHardwareBackend cameraBackend;
    private MainUiController uiController;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screenView = new MainScreenView(this);
        setContentView(screenView.root);

        cameraBackend = new CameraHardwareBackend(this, screenView.previewTextureView, null);
        uiController = new MainUiController(
                this,
                screenView,
                cameraBackend,
                new VideoAlignmentBackend(),
                new PolarisAlignmentBackend()
        );
        cameraBackend.setListener(uiController);
        uiController.initialize();
    }

    @Override protected void onResume() {
        super.onResume();
        if (uiController != null) uiController.onResume();
    }

    @Override protected void onPause() {
        if (uiController != null) uiController.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (uiController != null) {
            uiController.onDestroy();
            uiController = null;
        }
        cameraBackend = null;
        screenView = null;
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (uiController == null) return;
        if (requestCode == MainUiController.REQUEST_CAMERA_PERMISSION_FOR_UVC) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) uiController.onCameraPermissionGranted();
            else uiController.onCameraPermissionDenied();
        } else if (requestCode == MainUiController.REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) uiController.onLocationPermissionGranted();
            else uiController.onLocationPermissionDenied();
        }
    }

    /** Called by PolarisApplication for automatic USB-query on app start/attach. */
    private void requestCameraPermissionThenOpenUvc() {
        if (uiController != null) uiController.requestCameraPermissionThenOpenUvc();
    }

    /** Called by PolarisApplication to ensure buttons/panels exist before auto-query. */
    private void ensureUvcPreviewController() {
        if (uiController == null && screenView != null && cameraBackend != null) {
            uiController = new MainUiController(this, screenView, cameraBackend, new VideoAlignmentBackend(), new PolarisAlignmentBackend());
            cameraBackend.setListener(uiController);
            uiController.initialize();
        }
    }

    /** Called reflectively by PolarisApplication for USB attach diagnostics. */
    private void setUvcStatus(String statusText) {
        if (uiController != null) uiController.onCameraStatusChanged(statusText);
    }

    /** Called reflectively by PolarisApplication for USB attach diagnostics. */
    private void appendDebugLog(String message) {
        if (uiController != null) uiController.onCameraStatusChanged(message);
    }
}
