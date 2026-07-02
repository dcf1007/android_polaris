package com.dcf1007.androidpolaris.view;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Compatibility wrapper while MainScreenView is migrated to ui.ReticleOverlayView.
 * The implementation now lives in the UI layer.
 */
@Deprecated
public final class ReticleOverlayView extends com.dcf1007.androidpolaris.ui.ReticleOverlayView {
    public ReticleOverlayView(Context context) {
        super(context);
    }

    public ReticleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
