package com.dcf1007.androidpolaris.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal Camera2 preview controller.
 *
 * Responsibilities:
 * - enumerate Camera2 devices, including LENS_FACING_EXTERNAL entries;
 * - open one selected camera after Android camera permission is granted;
 * - build a repeating preview request into the supplied TextureView surface;
 * - cleanly close sessions, camera devices, and background threads.
 *
 * This intentionally does not use CameraX because the app requirement was to
 * refactor around Camera2. Device-specific Camera2 quirks are surfaced through
 * readable status callbacks instead of being swallowed silently.
 */
public final class Camera2PreviewController {
    public interface StatusListener {
        void onCameraStatus(String statusText);
    }

    private final Activity activity;
    private final TextureView textureView;
    private final StatusListener statusListener;
    private final CameraManager cameraManager;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice openedCameraDevice;
    private CameraCaptureSession previewSession;
    private String selectedCameraId;

    public Camera2PreviewController(Activity activity, TextureView textureView, StatusListener statusListener) {
        this.activity = activity;
        this.textureView = textureView;
        this.statusListener = statusListener;
        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        this.textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (selectedCameraId != null) {
                    openCamera(selectedCameraId);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // The next open/start cycle will choose a size for the new surface.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Frame available; no per-frame processing is required here.
            }
        });
    }

    public List<CameraDeviceInfo> listCameraDevices() {
        List<CameraDeviceInfo> cameraDevices = new ArrayList<>();
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                boolean isExternal = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL;
                String facingLabel = lensFacingLabel(lensFacing);
                cameraDevices.add(new CameraDeviceInfo(
                        cameraId,
                        "Camera2 " + cameraId + " — " + facingLabel,
                        isExternal
                ));
            }
        } catch (CameraAccessException exception) {
            publishStatus("Camera2 enumeration failed: " + exception.getMessage());
        }

        Collections.sort(cameraDevices, new Comparator<CameraDeviceInfo>() {
            @Override
            public int compare(CameraDeviceInfo left, CameraDeviceInfo right) {
                if (left.isExternalCamera != right.isExternalCamera) {
                    return left.isExternalCamera ? -1 : 1;
                }
                return left.cameraId.compareTo(right.cameraId);
            }
        });
        return cameraDevices;
    }

    public void startBackgroundThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("Camera2PreviewThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join(1500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    public void openCamera(String cameraId) {
        selectedCameraId = cameraId;
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publishStatus("Camera permission is required before opening Camera2 device " + cameraId + ".");
            return;
        }
        if (!textureView.isAvailable()) {
            publishStatus("Camera preview surface is not ready yet.");
            return;
        }

        startBackgroundThread();
        closeCamera();

        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cameraDevice) {
                    openedCameraDevice = cameraDevice;
                    publishStatus("Opened Camera2 device " + cameraDevice.getId() + ".");
                    startPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice cameraDevice) {
                    publishStatus("Camera2 device disconnected: " + cameraDevice.getId());
                    cameraDevice.close();
                    openedCameraDevice = null;
                }

                @Override
                public void onError(CameraDevice cameraDevice, int error) {
                    publishStatus("Camera2 device error " + error + " on camera " + cameraDevice.getId() + ".");
                    cameraDevice.close();
                    openedCameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException exception) {
            publishStatus("Could not open Camera2 device " + cameraId + ": " + exception.getMessage());
        } catch (SecurityException exception) {
            publishStatus("Camera permission denied while opening " + cameraId + ".");
        }
    }

    public void closeCamera() {
        if (previewSession != null) {
            previewSession.close();
            previewSession = null;
        }
        if (openedCameraDevice != null) {
            openedCameraDevice.close();
            openedCameraDevice = null;
        }
    }

    private void startPreviewSession() {
        if (openedCameraDevice == null || !textureView.isAvailable()) {
            return;
        }

        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                publishStatus("Camera preview texture is not available.");
                return;
            }

            Size previewSize = choosePreviewSize(openedCameraDevice.getId(), textureView.getWidth(), textureView.getHeight());
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            final CaptureRequest.Builder requestBuilder = openedCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(previewSurface);
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            openedCameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            previewSession = session;
                            try {
                                previewSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                                publishStatus("Camera2 preview running at " + previewSize.getWidth() + "×" + previewSize.getHeight() + ".");
                            } catch (CameraAccessException exception) {
                                publishStatus("Could not start Camera2 preview: " + exception.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            publishStatus("Camera2 preview session configuration failed.");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException exception) {
            publishStatus("Camera2 preview setup failed: " + exception.getMessage());
        }
    }

    private Size choosePreviewSize(String cameraId, int viewWidth, int viewHeight) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(Math.max(640, viewWidth), Math.max(480, viewHeight));
        }

        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(Math.max(640, viewWidth), Math.max(480, viewHeight));
        }

        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(sortedSizes, new Comparator<Size>() {
            @Override
            public int compare(Size left, Size right) {
                return Integer.compare(left.getWidth() * left.getHeight(), right.getWidth() * right.getHeight());
            }
        });

        int desiredWidth = Math.max(640, viewWidth);
        int desiredHeight = Math.max(480, viewHeight);
        for (Size candidate : sortedSizes) {
            if (candidate.getWidth() >= desiredWidth && candidate.getHeight() >= desiredHeight) {
                return candidate;
            }
        }
        return sortedSizes.get(sortedSizes.size() - 1);
    }

    private static String lensFacingLabel(Integer lensFacing) {
        if (lensFacing == null) {
            return "unknown facing";
        }
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "front";
            case CameraCharacteristics.LENS_FACING_BACK:
                return "back";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "external / UVC if exposed by Android";
            default:
                return "facing " + lensFacing;
        }
    }

    private void publishStatus(String statusText) {
        if (statusListener != null) {
            statusListener.onCameraStatus(statusText);
        }
    }
}
