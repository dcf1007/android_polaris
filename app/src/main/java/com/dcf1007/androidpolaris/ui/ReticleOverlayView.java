package com.dcf1007.androidpolaris.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.dcf1007.androidpolaris.backend.PolarisBackend;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.view.reticle.NativeCanvasReticle;
import com.dcf1007.androidpolaris.view.reticle.NativeReticleGeometry;

/**
 * Native Canvas reticle overlay drawn above the UVC preview.
 *
 * <p>This is UI code: it scales the reticle design-space into the Android view and asks the native
 * reticle tree to draw. It does not calculate Polaris positions or touch USB/camera state.</p>
 */
public class ReticleOverlayView extends View {
    private static final RectF RETICLE_DESIGN_BOUNDS = new RectF(
            0.0f,
            0.0f,
            (float) PolarisBackend.RETICLE_VIEWBOX_WIDTH,
            (float) PolarisBackend.RETICLE_VIEWBOX_HEIGHT
    );

    private final NativeCanvasReticle.Group reticleRoot = NativeReticleGeometry.createReticle();
    private AlignmentResult alignmentResult;

    public ReticleOverlayView(Context context) {
        super(context);
        initialize();
    }

    public ReticleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public void destroy() {
        alignmentResult = null;
    }

    public void setAlignmentResult(AlignmentResult alignmentResult) {
        this.alignmentResult = alignmentResult;
        invalidate();
    }

    private void initialize() {
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ViewportTransform transform = computeViewportTransform();
        if (transform.scale <= 0.0f) return;
        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        reticleRoot.draw(canvas, new NativeCanvasReticle.RenderContext(alignmentResult));
        canvas.restore();
    }

    private ViewportTransform computeViewportTransform() {
        float scale = (float) Math.min(getWidth() / RETICLE_DESIGN_BOUNDS.width(), getHeight() / RETICLE_DESIGN_BOUNDS.height());
        float usedWidth = RETICLE_DESIGN_BOUNDS.width() * scale;
        float usedHeight = RETICLE_DESIGN_BOUNDS.height() * scale;
        return new ViewportTransform(scale, (getWidth() - usedWidth) / 2.0f, (getHeight() - usedHeight) / 2.0f);
    }

    private static final class ViewportTransform {
        final float scale;
        final float offsetX;
        final float offsetY;
        ViewportTransform(float scale, float offsetX, float offsetY) {
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }
}
