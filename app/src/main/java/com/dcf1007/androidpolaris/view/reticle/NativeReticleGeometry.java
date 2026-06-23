package com.dcf1007.androidpolaris.view.reticle;

import android.graphics.Color;

/**
 * Full native Canvas geometry generated from the v4 reticle source drawing.
 *
 * <p>The original vector file is not shipped or parsed at runtime. Every source primitive is
 * represented as a native Canvas object in the same hierarchy and drawing order:
 * 49 groups, 484 lines, 7 circles, 2 paths and 54 text elements.</p>
 *
 * <p>Coordinate system: viewBox 0 0 1501.99 1498.19. NCP/epicentre:
 * (746.01, 746.43). Dynamic ids are preserved from the v4 source:</p>
 * <ul>
 *   <li>ha_x2F_value - active HA label</li>
 *   <li>date_x2F_value - fixed 0h date label</li>
 *   <li>polaris_x5F_position - live Polaris marker</li>
 *   <li>epicentrum_x2F_NCP - NCP marker</li>
 * </ul>
 */
public final class NativeReticleGeometry {
    static final int TRANSPARENT = Color.TRANSPARENT;
    static final int RED = Color.rgb(255, 0, 0);
    static final int WHITE = Color.rgb(255, 255, 255);
    static final int GREY = Color.rgb(127, 127, 127);
    static final int LIGHT_GREY = Color.rgb(204, 204, 204);
    static final int DATE_DIM = Color.rgb(127, 103, 0);
    static final int DATE_MEDIUM = Color.rgb(204, 165, 0);
    static final int DATE_YELLOW = Color.rgb(255, 204, 0);
    static final int DATE_RING = Color.rgb(148, 163, 184);
    static final int NCP_RED = Color.rgb(255, 59, 48);
    static final int POLARIS_PINK = Color.rgb(255, 128, 128);

    private NativeReticleGeometry() {}

    public static NativeCanvasReticle.Group createReticle() {
        NativeCanvasReticle.Group root = new NativeCanvasReticle.Group("Layer_1");
        NativeReticleGeometryPartPolar.append(root);
        NativeReticleGeometryPartDate.append(root);
        NativeReticleGeometryPartTime.append(root);
        NativeReticleGeometryPartDynamic.append(root);
        return root;
    }
}
