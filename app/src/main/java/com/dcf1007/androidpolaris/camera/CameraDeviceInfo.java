package com.dcf1007.androidpolaris.camera;

/**
 * Display and control data for one Camera2 camera.
 *
 * Camera2 is the preview pipeline for both built-in cameras and any OTG/UVC
 * camera that the Android device vendor exposes through the external-camera HAL.
 */
public final class CameraDeviceInfo {
    public final String cameraId;
    public final String displayName;
    public final boolean isExternalCamera;

    public CameraDeviceInfo(String cameraId, String displayName, boolean isExternalCamera) {
        this.cameraId = cameraId;
        this.displayName = displayName;
        this.isExternalCamera = isExternalCamera;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
