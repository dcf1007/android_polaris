package com.dcf1007.androidpolaris;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;

import com.dcf1007.androidpolaris.backend.CameraHardwareBackend;
import com.dcf1007.androidpolaris.backend.PolarisBackend;
import com.dcf1007.androidpolaris.backend.VideoAlignmentBackend;
import com.dcf1007.androidpolaris.ui.MainScreenView;
import com.dcf1007.androidpolaris.ui.MainUiController;

public final class MainActivity extends Activity {
    private MainScreenView screenView;
    private CameraHardwareBackend cameraBackend;
    private MainUiController uiController;
    private BroadcastReceiver usbAttachReceiver;
    private boolean usbAttachReceiverRegistered;
    private boolean automaticUsbQueryAlreadyTriggered;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screenView = new MainScreenView(this);
        setContentView(screenView.root);
        final MainUiController[] controllerHolder = new MainUiController[1];
        cameraBackend = new CameraHardwareBackend(this, screenView.previewTextureView, new CameraHardwareBackend.Listener() {
            @Override public void onCameraStatusChanged(String statusText) {
                if (controllerHolder[0] != null) controllerHolder[0].onCameraStatusChanged(statusText);
            }
            @Override public void onCameraCapabilitiesChanged(CameraHardwareBackend.Capabilities capabilities) {
                if (controllerHolder[0] != null) controllerHolder[0].onCameraCapabilitiesChanged(capabilities);
            }
        });
        uiController = new MainUiController(this, screenView, cameraBackend, new VideoAlignmentBackend(), new PolarisBackend());
        controllerHolder[0] = uiController;
        uiController.initialize();
        registerUsbAttachReceiver();
        maybeAutoQueryAttachedUsb(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (uiController != null) uiController.onResume();
        maybeAutoQueryAttachedUsb(false);
    }

    @Override protected void onPause() {
        if (uiController != null) uiController.onPause();
        automaticUsbQueryAlreadyTriggered = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterUsbAttachReceiver();
        if (uiController != null) {
            uiController.onDestroy();
            uiController = null;
        }
        cameraBackend = null;
        screenView = null;
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] ignoredPermissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, ignoredPermissions, grantResults);
        if (uiController == null) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == 0;
        if (requestCode == MainUiController.REQUEST_CAMERA_PERMISSION_FOR_UVC) {
            if (granted) uiController.onCameraPermissionGranted();
            else uiController.onCameraPermissionDenied();
        } else if (requestCode == MainUiController.REQUEST_LOCATION_PERMISSION) {
            if (granted) uiController.onLocationPermissionGranted();
            else uiController.onLocationPermissionDenied();
        }
    }

    private void registerUsbAttachReceiver() {
        if (usbAttachReceiverRegistered) return;
        usbAttachReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) maybeAutoQueryAttachedUsb(true);
            }
        };
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbAttachReceiver, filter);
        usbAttachReceiverRegistered = true;
    }

    private void unregisterUsbAttachReceiver() {
        if (!usbAttachReceiverRegistered || usbAttachReceiver == null) return;
        try { unregisterReceiver(usbAttachReceiver); }
        catch (Throwable ignored) { }
        usbAttachReceiverRegistered = false;
        usbAttachReceiver = null;
    }

    private void maybeAutoQueryAttachedUsb(boolean forceQuery) {
        if (uiController == null) return;
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        boolean hasUsbDevice = usbManager != null && !usbManager.getDeviceList().isEmpty();
        if (!hasUsbDevice) {
            automaticUsbQueryAlreadyTriggered = false;
            return;
        }
        if (!forceQuery && automaticUsbQueryAlreadyTriggered) return;
        automaticUsbQueryAlreadyTriggered = true;
        uiController.onCameraStatusChanged("Raw USB device detected. Passing candidate to libuvc query path.");
        uiController.requestCameraPermissionThenOpenUvc();
    }
}
