package com.dcf1007.androidpolaris.view.reticle;

import android.graphics.Color;

/**
 * Full native Canvas geometry generated from all_together_v4.svg.
 *
 * <p>No SVG or CSV asset is used at runtime. The original SVG hierarchy is reconstructed as native
 * Canvas objects: 49 groups, 484 lines, 7 circles, 2 paths and 54 text elements. The drawing order,
 * ids, colors, stroke widths, dash patterns and coordinates are preserved from SVG v4.</p>
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

    static final class BuildState {
        final NativeSvgReticle.Group[] groups = new NativeSvgReticle.Group[50];
    }

    public static NativeSvgReticle.Group createReticle() {
        BuildState state = new BuildState();
        state.groups[0] = new NativeSvgReticle.Group("Layer_1");
        NativeReticleGeometryPart1.add(state);
        NativeReticleGeometryPart2.add(state);
        NativeReticleGeometryPart3.add(state);
        NativeReticleGeometryPart4.add(state);
        return state.groups[0];
    }

    static void g(BuildState state, int index, int parentIndex, String id) {
        NativeSvgReticle.Group group = state.groups[parentIndex].group(id);
        state.groups[index] = group;
    }

    static void l(BuildState state, int parentIndex, String id, float x1, float y1, float x2, float y2, int strokeColor, float strokeWidth, float[] dashPattern) {
        state.groups[parentIndex].line(id, x1, y1, x2, y2, strokeColor, strokeWidth, dashPattern);
    }

    static void c(BuildState state, int parentIndex, String id, float cx, float cy, float radius, int fillColor, int strokeColor, float strokeWidth) {
        state.groups[parentIndex].circle(id, cx, cy, radius, fillColor, strokeColor, strokeWidth);
    }

    static void p(BuildState state, int parentIndex, String id, String pathData, int fillColor, int strokeColor, float strokeWidth, float[] dashPattern) {
        state.groups[parentIndex].path(id, pathData, fillColor, strokeColor, strokeWidth, dashPattern);
    }

    static void t(BuildState state, int parentIndex, String id, float x, float y, String value, int fillColor, float textSize, boolean bold) {
        state.groups[parentIndex].text(id, x, y, value, fillColor, textSize, bold);
    }
}
