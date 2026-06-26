package com.dcf1007.androidpolaris.camera;

import com.jiangdg.uvc.UVCCamera;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflective bridge to the lower-level libuvc UVCCamera object owned by AUSBC's CameraUVC.
 *
 * <p>AUSBC exposes brightness/contrast/gain/render effects through its public camera wrapper,
 * but the exposure and auto-exposure calls live in the lower-level UVCCamera class. CameraUVC
 * keeps that instance in a private mUvcCamera field, so this class performs a narrow, isolated
 * reflection bridge. All failures are reported as unsupported instead of crashing the preview.</p>
 */
public final class UvcExposureBridge {
    private static final int MANUAL_EXPOSURE_MODE = 1;
    private static final int AUTO_EXPOSURE_MODE = 2;

    private final UVCCamera uvcCamera;
    private final Field nativePtrField;
    private final Field exposureMinField;
    private final Field exposureMaxField;
    private final Field exposureDefField;
    private final Field exposureModeDefField;
    private final Method nativeUpdateExposureLimit;
    private final Method nativeGetExposure;
    private final Method nativeSetExposure;
    private final Method nativeUpdateExposureModeLimit;
    private final Method nativeGetExposureMode;
    private final Method nativeSetExposureMode;

    private UvcExposureBridge(UVCCamera uvcCamera) throws ReflectiveOperationException {
        this.uvcCamera = uvcCamera;
        Class<UVCCamera> cls = UVCCamera.class;
        nativePtrField = accessibleField(cls, "mNativePtr");
        exposureMinField = accessibleField(cls, "mExposureMin");
        exposureMaxField = accessibleField(cls, "mExposureMax");
        exposureDefField = accessibleField(cls, "mExposureDef");
        exposureModeDefField = accessibleField(cls, "mExposureModeDef");
        nativeUpdateExposureLimit = accessibleMethod(cls, "nativeUpdateExposureLimit", long.class);
        nativeGetExposure = accessibleMethod(cls, "nativeGetExposure", long.class);
        nativeSetExposure = accessibleMethod(cls, "nativeSetExposure", long.class, int.class);
        nativeUpdateExposureModeLimit = accessibleMethod(cls, "nativeUpdateExposureModeLimit", long.class);
        nativeGetExposureMode = accessibleMethod(cls, "nativeGetExposureMode", long.class);
        nativeSetExposureMode = accessibleMethod(cls, "nativeSetExposureMode", long.class, int.class);
    }

    public static UvcExposureBridge fromAusbcCamera(Object ausbcCamera) {
        UVCCamera uvc = findPrivateUvcCamera(ausbcCamera);
        if (uvc == null) {
            return null;
        }
        try {
            return new UvcExposureBridge(uvc);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public boolean supportsAutoExposureMode() {
        try {
            return uvcCamera.checkSupportFlag(UVCCamera.CTRL_AE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean supportsAbsoluteExposure() {
        try {
            return uvcCamera.checkSupportFlag(UVCCamera.CTRL_AE_ABS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isAutoExposureEnabled() {
        try {
            updateExposureModeLimit();
            int mode = (Integer) nativeGetExposureMode.invoke(null, nativePtr());
            return mode != MANUAL_EXPOSURE_MODE;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public boolean setAutoExposureEnabled(boolean enabled) {
        try {
            updateExposureModeLimit();
            int mode = enabled ? preferredAutoExposureMode() : MANUAL_EXPOSURE_MODE;
            nativeSetExposureMode.invoke(null, nativePtr(), mode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int getExposurePercent() {
        try {
            updateExposureLimit();
            int raw = (Integer) nativeGetExposure.invoke(null, nativePtr());
            return rawToPercent(raw);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public boolean setExposurePercent(int percent) {
        try {
            updateExposureLimit();
            nativeSetExposure.invoke(null, nativePtr(), percentToRaw(clamp(percent, 0, 100)));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String describeExposureRange() {
        try {
            updateExposureLimit();
            return "raw min=" + exposureMinField.getInt(uvcCamera)
                    + ", max=" + exposureMaxField.getInt(uvcCamera)
                    + ", def=" + exposureDefField.getInt(uvcCamera);
        } catch (Throwable ignored) {
            return "raw range unavailable";
        }
    }

    private void updateExposureLimit() throws ReflectiveOperationException {
        nativeUpdateExposureLimit.invoke(uvcCamera, nativePtr());
    }

    private void updateExposureModeLimit() throws ReflectiveOperationException {
        nativeUpdateExposureModeLimit.invoke(uvcCamera, nativePtr());
    }

    private long nativePtr() throws IllegalAccessException {
        return nativePtrField.getLong(uvcCamera);
    }

    private int preferredAutoExposureMode() throws IllegalAccessException {
        int def = exposureModeDefField.getInt(uvcCamera);
        return def == MANUAL_EXPOSURE_MODE ? AUTO_EXPOSURE_MODE : def;
    }

    private int rawToPercent(int raw) throws IllegalAccessException {
        int min = exposureMinField.getInt(uvcCamera);
        int max = exposureMaxField.getInt(uvcCamera);
        int range = Math.abs(max - min);
        if (range <= 0) {
            return 0;
        }
        return clamp(Math.round((raw - min) * 100.0f / range), 0, 100);
    }

    private int percentToRaw(int percent) throws IllegalAccessException {
        int min = exposureMinField.getInt(uvcCamera);
        int max = exposureMaxField.getInt(uvcCamera);
        return Math.round(min + (Math.abs(max - min) * percent / 100.0f));
    }

    private static Field accessibleField(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method accessibleMethod(Class<?> cls, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = cls.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static UVCCamera findPrivateUvcCamera(Object ausbcCamera) {
        Class<?> cls = ausbcCamera == null ? null : ausbcCamera.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField("mUvcCamera");
                field.setAccessible(true);
                Object value = field.get(ausbcCamera);
                return value instanceof UVCCamera ? (UVCCamera) value : null;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
