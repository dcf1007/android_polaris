package com.dcf1007.androidpolaris.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.SeekBar;

/**
 * Backend for alignment/position/transparency panel state.
 *
 * <p>The UI owns sliders and checkboxes. This backend owns the persisted numeric model and the
 * conversion between slider progress and physical UI values.</p>
 */
public final class VideoAlignmentBackend {
    public static final String FIT_COVER = "Cover / crop";
    public static final String FIT_CONTAIN = "Contain / no crop";
    public static final String FIT_STRETCH = "Stretch";
    public static final String[] FIT_OPTIONS = {FIT_COVER, FIT_CONTAIN, FIT_STRETCH};
    private static final String SETTINGS = "android_polaris_overlay_settings";

    public static final class State {
        public float offsetXPercent;
        public float offsetYPercent;
        public float widthPercent = 100.0f;
        public float heightPercent = 100.0f;
        public float rotationDegrees;
        public float reticleOpacityPercent = 100.0f;
        public float videoOpacityPercent = 100.0f;
        public boolean mirrorVideo;
        public boolean lockScale = true;
        public String fitMode = FIT_COVER;
    }

    public State load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        State state = new State();
        state.offsetXPercent = preferences.getFloat("videoOffsetXPercent", 0.0f);
        state.offsetYPercent = preferences.getFloat("videoOffsetYPercent", 0.0f);
        state.widthPercent = preferences.getFloat("videoWidthPercent", 100.0f);
        state.heightPercent = preferences.getFloat("videoHeightPercent", 100.0f);
        state.rotationDegrees = preferences.getFloat("videoRotationDegrees", 0.0f);
        state.reticleOpacityPercent = preferences.getFloat("reticleOpacityPercent", 100.0f);
        state.videoOpacityPercent = preferences.getFloat("videoOpacityPercent", 100.0f);
        state.mirrorVideo = preferences.getBoolean("mirrorVideo", false);
        state.lockScale = preferences.getBoolean("lockVideoScale", true);
        state.fitMode = sanitizeFitMode(preferences.getString("videoFitMode", FIT_COVER));
        if (state.lockScale) state.heightPercent = state.widthPercent;
        return state;
    }

    public void save(Context context, State state) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
                .putFloat("videoOffsetXPercent", state.offsetXPercent)
                .putFloat("videoOffsetYPercent", state.offsetYPercent)
                .putFloat("videoWidthPercent", state.widthPercent)
                .putFloat("videoHeightPercent", state.heightPercent)
                .putFloat("videoRotationDegrees", state.rotationDegrees)
                .putFloat("reticleOpacityPercent", state.reticleOpacityPercent)
                .putFloat("videoOpacityPercent", state.videoOpacityPercent)
                .putBoolean("mirrorVideo", state.mirrorVideo)
                .putBoolean("lockVideoScale", state.lockScale)
                .putString("videoFitMode", sanitizeFitMode(state.fitMode))
                .apply();
    }

    public State defaultState() { return new State(); }

    public String sanitizeFitMode(String rawMode) {
        if (FIT_CONTAIN.equals(rawMode)) return FIT_CONTAIN;
        if (FIT_STRETCH.equals(rawMode) || "Fill / stretch".equals(rawMode) || "fill".equals(rawMode)) return FIT_STRETCH;
        return FIT_COVER;
    }

    public int signedPercentToProgress(float percent) { return clamp(Math.round((percent + 100.0f) * 10.0f), 0, 2000); }
    public float signedPercentFromProgress(SeekBar seekBar) { return seekBar.getProgress() / 10.0f - 100.0f; }
    public int sizePercentToProgress(float percent) { return clamp(Math.round((percent - 20.0f) * 10.0f), 0, 2800); }
    public float sizePercentFromProgress(SeekBar seekBar) { return 20.0f + seekBar.getProgress() / 10.0f; }
    public int rotationDegreesToProgress(float degrees) { return clamp(Math.round((degrees + 180.0f) * 10.0f), 0, 3600); }
    public float rotationDegreesFromProgress(SeekBar seekBar) { return seekBar.getProgress() / 10.0f - 180.0f; }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
