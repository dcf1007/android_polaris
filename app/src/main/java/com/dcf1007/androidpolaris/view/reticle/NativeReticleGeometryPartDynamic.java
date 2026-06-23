package com.dcf1007.androidpolaris.view.reticle;

/** Generated native reticle geometry segment. */
final class NativeReticleGeometryPartDynamic {
    private NativeReticleGeometryPartDynamic() {}

    static void append(NativeCanvasReticle.Group root) {
        NativeCanvasReticle.Group g47 = root.group("HA_x5F_indicator");
        g47.line("", 746.01f, 144.7f, 746.01f, 68.73f, NativeReticleGeometry.DATE_YELLOW, 2f, null);
        g47.path("", "M746.01,77.73l-15,28.97h30l-15-28.97Z", NativeReticleGeometry.DATE_YELLOW, NativeReticleGeometry.TRANSPARENT, 0f, null);
        g47.text("ha_x2F_value", 757.62f, 98.88f, "hh:mm:ss", NativeReticleGeometry.DATE_YELLOW, 18f, true);
        NativeCanvasReticle.Group g48 = root.group("_x30_h_x5F_indicator");
        g48.line("", 746.01f, 144.7f, 746.01f, 68.73f, NativeReticleGeometry.WHITE, 2f, null);
        g48.path("", "M746.01,135.67l-15-28.97h30l-15,28.97Z", NativeReticleGeometry.WHITE, NativeReticleGeometry.TRANSPARENT, 0f, null);
        g48.text("date_x2F_value", 757.62f, 125.52f, "DD/MM", NativeReticleGeometry.WHITE, 18f, true);
        NativeCanvasReticle.Group g49 = root.group("polaris_x5F_indicator");
        g49.line("", 746.01f, 746.43f, 683.52f, 1069.41f, NativeReticleGeometry.POLARIS_PINK, 2f, new float[]{7f, 8f});
        g49.circle("epicentrum_x2F_NCP", 746.01f, 746.43f, 4.5f, NativeReticleGeometry.NCP_RED, NativeReticleGeometry.TRANSPARENT, 0f);
        g49.circle("polaris_x5F_position", 683.52f, 1069.41f, 7.5f, NativeReticleGeometry.POLARIS_PINK, NativeReticleGeometry.TRANSPARENT, 0f);
    }
}
