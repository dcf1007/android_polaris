package com.dcf1007.androidpolaris.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.util.UiFormatting;

import java.io.IOException;
import java.util.Locale;

/** Renders the supplied reticle SVG assets and draws the live calculated markers. */
public final class ReticleOverlayView extends View {
    private static final int NCP_RED = Color.rgb(255, 59, 48);
    private static final int POLARIS_PINK = Color.rgb(255, 128, 128);
    private static final int YELLOW = Color.rgb(255, 204, 0);
    private static final int WHITE = Color.WHITE;
    private static final String DATE_POLAR_SCOPE_SVG_ASSET = "reticle_date_polar_scope.svg";
    private static final String TIME_SCALE_SVG_ASSET = "reticle_time_scale.svg";
    private static final RectF SVG_VIEWPORT = new RectF(0.0f, 0.0f,
            (float) PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH,
            (float) PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT);

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trianglePath = new Path();

    private SVG datePolarScopeSvg;
    private SVG timeScaleSvg;
    private AlignmentResult alignmentResult;
    private String assetWarning;

    public ReticleOverlayView(Context context) {
        super(context);
        initialize(context);
    }

    public ReticleOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    public void destroy() {
        datePolarScopeSvg = null;
        timeScaleSvg = null;
    }

    public void setAlignmentResult(AlignmentResult alignmentResult) {
        this.alignmentResult = alignmentResult;
        invalidate();
    }

    private void initialize(Context context) {
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.TRANSPARENT);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        loadSvgAssets(context);
    }

    private void loadSvgAssets(Context context) {
        try {
            datePolarScopeSvg = loadSvgAsset(context, DATE_POLAR_SCOPE_SVG_ASSET);
            timeScaleSvg = loadSvgAsset(context, TIME_SCALE_SVG_ASSET);
        } catch (IOException | SVGParseException exception) {
            assetWarning = "Reticle SVG load failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static SVG loadSvgAsset(Context context, String assetName) throws IOException, SVGParseException {
        SVG svg = SVG.getFromAsset(context.getAssets(), assetName);
        svg.setDocumentWidth((float) PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH);
        svg.setDocumentHeight((float) PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT);
        return svg;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ViewportTransform transform = computeViewportTransform();
        if (transform.scale <= 0.0f) return;
        drawStaticReticleSvgLayers(canvas, transform);
        drawDynamicOverlay(canvas, transform);
        if (assetWarning != null) drawAssetWarning(canvas);
    }

    private void drawStaticReticleSvgLayers(Canvas canvas, ViewportTransform transform) {
        float datePolarRotationDegrees = alignmentResult == null ? 0.0f : (float) alignmentResult.dateAndPolarReticleRotationDegrees;
        drawSvgLayer(canvas, datePolarScopeSvg, transform, datePolarRotationDegrees);
        drawSvgLayer(canvas, timeScaleSvg, transform, 0.0f);
    }

    private void drawSvgLayer(Canvas canvas, SVG svg, ViewportTransform transform, float rotationDegrees) {
        if (svg == null) return;
        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        if (rotationDegrees != 0.0f) {
            canvas.rotate(rotationDegrees,
                    (float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                    (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y);
        }
        svg.renderToCanvas(canvas, SVG_VIEWPORT);
        canvas.restore();
    }

    private void drawDynamicOverlay(Canvas canvas, ViewportTransform transform) {
        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        drawNorthCelestialPole(canvas);
        drawZeroHourIndicator(canvas, alignmentResult);
        if (alignmentResult != null) {
            drawHourAngleIndicator(canvas, alignmentResult);
            drawPolarisTarget(canvas, alignmentResult);
        }
        canvas.restore();
    }

    private void drawNorthCelestialPole(Canvas canvas) {
        fillPaint.setColor(NCP_RED);
        canvas.drawCircle((float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y, 4.5f, fillPaint);
    }

    private void drawZeroHourIndicator(Canvas canvas, AlignmentResult result) {
        strokePaint.setPathEffect(null);
        strokePaint.setColor(WHITE);
        strokePaint.setStrokeWidth(2f);
        canvas.drawLine(746.01f, 144.70f, 746.01f, 68.73f, strokePaint);
        trianglePath.reset();
        trianglePath.moveTo(746.01f, 135.67f);
        trianglePath.lineTo(731.01f, 106.70f);
        trianglePath.lineTo(761.01f, 106.70f);
        trianglePath.close();
        fillPaint.setColor(WHITE);
        canvas.drawPath(trianglePath, fillPaint);
        String label = result == null ? "DD/MM" : formatDayMonth(result.zeroHourDateMonth, result.zeroHourDateDay);
        textPaint.setColor(WHITE);
        textPaint.setTextSize(18f);
        textPaint.setFakeBoldText(true);
        canvas.drawText(label, 757.62f, 125.52f, textPaint);
    }

    private void drawHourAngleIndicator(Canvas canvas, AlignmentResult result) {
        canvas.save();
        canvas.rotate((float) result.activeHourAngleDisplayAngleDegrees,
                (float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y);
        strokePaint.setPathEffect(null);
        strokePaint.setColor(YELLOW);
        strokePaint.setStrokeWidth(2f);
        canvas.drawLine(746.01f, 144.70f, 746.01f, 68.73f, strokePaint);
        trianglePath.reset();
        trianglePath.moveTo(746.01f, 77.73f);
        trianglePath.lineTo(731.01f, 106.70f);
        trianglePath.lineTo(761.01f, 106.70f);
        trianglePath.close();
        fillPaint.setColor(YELLOW);
        canvas.drawPath(trianglePath, fillPaint);
        textPaint.setColor(YELLOW);
        textPaint.setTextSize(18f);
        textPaint.setFakeBoldText(true);
        canvas.drawText(UiFormatting.formatHours(result.activeHourAngleHours), 757.62f, 98.88f, textPaint);
        canvas.restore();
    }

    private void drawPolarisTarget(Canvas canvas, AlignmentResult result) {
        strokePaint.setColor(POLARIS_PINK);
        strokePaint.setStrokeWidth(2f);
        strokePaint.setPathEffect(new DashPathEffect(new float[]{7f, 8f}, 0f));
        canvas.drawLine((float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y,
                (float) result.markerSvgX,
                (float) result.markerSvgY,
                strokePaint);
        strokePaint.setPathEffect(null);
        fillPaint.setColor(POLARIS_PINK);
        canvas.drawCircle((float) result.markerSvgX, (float) result.markerSvgY, 7.5f, fillPaint);
    }

    private void drawAssetWarning(Canvas canvas) {
        textPaint.setColor(Color.RED);
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);
        canvas.drawText(assetWarning, 24f, 42f, textPaint);
    }

    private ViewportTransform computeViewportTransform() {
        float scale = (float) Math.min(getWidth() / PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH,
                getHeight() / PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT);
        float usedWidth = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH * scale;
        float usedHeight = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT * scale;
        return new ViewportTransform(scale, (getWidth() - usedWidth) / 2.0f, (getHeight() - usedHeight) / 2.0f);
    }

    private static String formatDayMonth(int month, int day) {
        return String.format(Locale.US, "%02d/%02d", day, month);
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
