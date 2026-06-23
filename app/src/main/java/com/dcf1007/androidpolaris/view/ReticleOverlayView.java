package com.dcf1007.androidpolaris.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.util.UiFormatting;

/**
 * Native reticle overlay drawn over the camera preview.
 *
 * The overlay uses the same coordinate source as the verified SVG:
 * - viewBox = 1501.99 x 1498.19;
 * - NCP/epicentre = (746.01, 746.43);
 * - 2012/2020/2028 Polaris rings centred on the same point.
 *
 * Drawing in SVG-space first and transforming to view-space keeps the native
 * app numerically aligned with the browser implementation even when the Android
 * view has a different aspect ratio.
 */
public final class ReticleOverlayView extends View {
    private static final int RED = Color.rgb(255, 0, 0);
    private static final int NCP_RED = Color.rgb(255, 59, 48);
    private static final int POLARIS_PINK = Color.rgb(255, 128, 128);
    private static final int YELLOW = Color.rgb(255, 204, 0);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(170, 170, 170);

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private AlignmentResult alignmentResult;
    private String centerLabel = "NCP";

    public ReticleOverlayView(Context context) {
        super(context);
        configurePaints();
    }

    public ReticleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        configurePaints();
    }

    public void setAlignmentResult(AlignmentResult alignmentResult) {
        this.alignmentResult = alignmentResult;
        invalidate();
    }

    public void setCenterLabel(String centerLabel) {
        this.centerLabel = centerLabel;
        invalidate();
    }

    private void configurePaints() {
        setWillNotDraw(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(WHITE);
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ViewportTransform transform = computeViewportTransform();

        drawReferenceReticle(canvas, transform);
        if (alignmentResult != null) {
            drawPolarisTarget(canvas, transform, alignmentResult);
            drawHourAnglePointer(canvas, transform, alignmentResult);
            drawReadoutOverlay(canvas, alignmentResult);
        }
    }

    private void drawReferenceReticle(Canvas canvas, ViewportTransform transform) {
        float centerX = transform.svgToViewX(PolarisAlignmentCalculator.RETICLE_CENTER_X);
        float centerY = transform.svgToViewY(PolarisAlignmentCalculator.RETICLE_CENTER_Y);

        strokePaint.setColor(RED);
        strokePaint.setStrokeWidth(2.0f * transform.scale);

        // Cardinal crosshair copied from the verified SVG line endpoints.
        canvas.drawLine(
                transform.svgToViewX(746.01), transform.svgToViewY(326.43),
                transform.svgToViewX(746.01), transform.svgToViewY(1166.43),
                strokePaint
        );
        canvas.drawLine(
                transform.svgToViewX(326.01), transform.svgToViewY(746.43),
                transform.svgToViewX(1166.01), transform.svgToViewY(746.43),
                strokePaint
        );

        // Polaris year-ring references. The 2012/2020/2028 circle radii match
        // the current SVG. The 2016/2024/2032 printed ticks are omitted here to
        // keep the mobile overlay readable over a live camera feed.
        drawSvgCircle(canvas, transform, 358.5, RED, 2.0f);
        drawSvgCircle(canvas, transform, 342.5, RED, 2.0f);
        drawSvgCircle(canvas, transform, 326.5, RED, 2.0f);

        // Outer time and date rings are simplified to circular references. The
        // precise labels remain in the calculation readouts.
        drawSvgCircle(canvas, transform, 590.76, MUTED, 1.4f);
        drawSvgCircle(canvas, transform, 694.73, WHITE, 1.4f);

        fillPaint.setColor(NCP_RED);
        canvas.drawCircle(centerX, centerY, 4.5f * transform.scale, fillPaint);

        textPaint.setColor(WHITE);
        textPaint.setTextSize(24f * transform.scale);
        canvas.drawText(centerLabel, centerX + 10f * transform.scale, centerY - 10f * transform.scale, textPaint);
    }

    private void drawSvgCircle(Canvas canvas, ViewportTransform transform, double radiusSvg, int color, float strokeWidthSvg) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(strokeWidthSvg * transform.scale);
        float centerX = transform.svgToViewX(PolarisAlignmentCalculator.RETICLE_CENTER_X);
        float centerY = transform.svgToViewY(PolarisAlignmentCalculator.RETICLE_CENTER_Y);
        float radius = (float) (radiusSvg * transform.scale);
        canvas.drawOval(new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius), strokePaint);
    }

    private void drawPolarisTarget(Canvas canvas, ViewportTransform transform, AlignmentResult result) {
        float centerX = transform.svgToViewX(PolarisAlignmentCalculator.RETICLE_CENTER_X);
        float centerY = transform.svgToViewY(PolarisAlignmentCalculator.RETICLE_CENTER_Y);
        float markerX = transform.svgToViewX(result.markerSvgX);
        float markerY = transform.svgToViewY(result.markerSvgY);

        strokePaint.setColor(POLARIS_PINK);
        strokePaint.setStrokeWidth(2.2f * transform.scale);
        strokePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{7f * transform.scale, 8f * transform.scale}, 0f));
        canvas.drawLine(centerX, centerY, markerX, markerY, strokePaint);
        strokePaint.setPathEffect(null);

        fillPaint.setColor(POLARIS_PINK);
        canvas.drawCircle(markerX, markerY, 8.0f * transform.scale, fillPaint);
    }

    private void drawHourAnglePointer(Canvas canvas, ViewportTransform transform, AlignmentResult result) {
        double clockAngleRadians = (result.polarisHourAngleHours * 15.0) * Math.PI / 180.0;
        double outerRadiusSvg = 694.73;
        double innerRadiusSvg = 600.0;

        float x1 = transform.svgToViewX(PolarisAlignmentCalculator.RETICLE_CENTER_X + innerRadiusSvg * Math.sin(clockAngleRadians));
        float y1 = transform.svgToViewY(PolarisAlignmentCalculator.RETICLE_CENTER_Y - innerRadiusSvg * Math.cos(clockAngleRadians));
        float x2 = transform.svgToViewX(PolarisAlignmentCalculator.RETICLE_CENTER_X + outerRadiusSvg * Math.sin(clockAngleRadians));
        float y2 = transform.svgToViewY(PolarisAlignmentCalculator.RETICLE_CENTER_Y - outerRadiusSvg * Math.cos(clockAngleRadians));

        strokePaint.setColor(YELLOW);
        strokePaint.setStrokeWidth(3.0f * transform.scale);
        canvas.drawLine(x1, y1, x2, y2, strokePaint);
    }

    private void drawReadoutOverlay(Canvas canvas, AlignmentResult result) {
        textPaint.setColor(YELLOW);
        textPaint.setTextSize(30f);
        canvas.drawText("HA " + UiFormatting.formatHours(result.polarisHourAngleHours), 24f, 42f, textPaint);

        textPaint.setColor(POLARIS_PINK);
        canvas.drawText("Polaris target", 24f, 80f, textPaint);
    }

    private ViewportTransform computeViewportTransform() {
        float scale = (float) Math.min(
                getWidth() / PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH,
                getHeight() / PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT
        );
        float usedWidth = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH * scale;
        float usedHeight = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT * scale;
        float offsetX = (getWidth() - usedWidth) / 2.0f;
        float offsetY = (getHeight() - usedHeight) / 2.0f;
        return new ViewportTransform(scale, offsetX, offsetY);
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

        float svgToViewX(double svgX) {
            return offsetX + (float) svgX * scale;
        }

        float svgToViewY(double svgY) {
            return offsetY + (float) svgY * scale;
        }
    }
}
