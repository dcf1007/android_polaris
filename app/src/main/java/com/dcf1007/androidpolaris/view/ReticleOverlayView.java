package com.dcf1007.androidpolaris.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.util.UiFormatting;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws the reticle artwork from compact offline primitive assets. */
public final class ReticleOverlayView extends View {
    private static final int NCP_RED = Color.rgb(255, 59, 48);
    private static final int POLARIS_PINK = Color.rgb(255, 128, 128);
    private static final int YELLOW = Color.rgb(255, 204, 0);
    private static final int WHITE = Color.WHITE;
    private static final String DATE_POLAR_ASSET = "reticle_date_polar_scope.csv";
    private static final String TIME_ZERO_ASSET = "reticle_time_zero_static.csv";

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trianglePath = new Path();
    private final List<Primitive> datePolarPrimitives = new ArrayList<>();
    private final List<Primitive> timeZeroPrimitives = new ArrayList<>();

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
        datePolarPrimitives.clear();
        timeZeroPrimitives.clear();
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
        loadPrimitiveAssets(context);
    }

    private void loadPrimitiveAssets(Context context) {
        try {
            datePolarPrimitives.addAll(loadPrimitiveFile(context, DATE_POLAR_ASSET));
            timeZeroPrimitives.addAll(loadPrimitiveFile(context, TIME_ZERO_ASSET));
        } catch (Exception exception) {
            assetWarning = "Reticle asset load failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ViewportTransform transform = computeViewportTransform();
        drawReticleArtwork(canvas, transform);
        drawDynamicOverlay(canvas, transform);
        if (assetWarning != null) {
            drawAssetWarning(canvas);
        }
    }

    private void drawReticleArtwork(Canvas canvas, ViewportTransform transform) {
        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        if (alignmentResult != null) {
            canvas.rotate((float) alignmentResult.dateAndPolarReticleRotationDegrees,
                    (float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                    (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y);
        }
        drawPrimitives(canvas, datePolarPrimitives);
        canvas.restore();

        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        drawPrimitives(canvas, timeZeroPrimitives);
        canvas.restore();
    }

    private void drawDynamicOverlay(Canvas canvas, ViewportTransform transform) {
        canvas.save();
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scale, transform.scale);
        drawNorthCelestialPole(canvas);
        if (alignmentResult != null) {
            drawHourAngleIndicator(canvas, alignmentResult);
            drawZeroHourDate(canvas, alignmentResult);
            drawPolarisTarget(canvas, alignmentResult);
        }
        canvas.restore();
    }

    private void drawPrimitives(Canvas canvas, List<Primitive> primitives) {
        for (Primitive primitive : primitives) {
            if (primitive.kind == 'L') {
                strokePaint.setColor(primitive.color);
                strokePaint.setStrokeWidth(primitive.strokeWidth);
                strokePaint.setPathEffect(null);
                canvas.drawLine(primitive.x1, primitive.y1, primitive.x2, primitive.y2, strokePaint);
            } else if (primitive.kind == 'C') {
                strokePaint.setColor(primitive.color);
                strokePaint.setStrokeWidth(primitive.strokeWidth);
                strokePaint.setPathEffect(null);
                canvas.drawCircle(primitive.x1, primitive.y1, primitive.radius, strokePaint);
            } else if (primitive.kind == 'T') {
                textPaint.setColor(primitive.color);
                textPaint.setTextSize(primitive.textSize);
                textPaint.setFakeBoldText(primitive.boldText);
                canvas.drawText(primitive.text, primitive.x1, primitive.y1, textPaint);
            }
        }
    }

    private void drawNorthCelestialPole(Canvas canvas) {
        fillPaint.setColor(NCP_RED);
        canvas.drawCircle((float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y,
                4.5f,
                fillPaint);
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

    private void drawZeroHourDate(Canvas canvas, AlignmentResult result) {
        textPaint.setColor(WHITE);
        textPaint.setTextSize(18f);
        textPaint.setFakeBoldText(true);
        canvas.drawText(formatDayMonth(result.zeroHourDateMonth, result.zeroHourDateDay), 757.62f, 125.52f, textPaint);
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
        float scale = (float) Math.min(
                getWidth() / PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH,
                getHeight() / PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT
        );
        float usedWidth = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_WIDTH * scale;
        float usedHeight = (float) PolarisAlignmentCalculator.SVG_VIEWBOX_HEIGHT * scale;
        return new ViewportTransform(scale, (getWidth() - usedWidth) / 2.0f, (getHeight() - usedHeight) / 2.0f);
    }

    private static List<Primitive> loadPrimitiveFile(Context context, String assetName) throws Exception {
        List<Primitive> primitives = new ArrayList<>();
        try (InputStream inputStream = context.getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                primitives.add(parsePrimitive(line));
            }
        }
        return primitives;
    }

    private static Primitive parsePrimitive(String line) {
        String[] parts = line.split(",", 7);
        char kind = parts[0].charAt(0);
        if (kind == 'L') {
            return Primitive.line(
                    parseFloat(parts[1]), parseFloat(parts[2]),
                    parseFloat(parts[3]), parseFloat(parts[4]),
                    parseColor(parts[5]), parseFloat(parts[6])
            );
        }
        if (kind == 'C') {
            return Primitive.circle(
                    parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3]),
                    parseColor(parts[4]), parseFloat(parts[5])
            );
        }
        if (kind == 'T') {
            return Primitive.text(
                    parseFloat(parts[1]), parseFloat(parts[2]), parts[3],
                    parseColor(parts[4]), parseFloat(parts[5]), "1".equals(parts[6])
            );
        }
        throw new IllegalArgumentException("Unknown primitive kind: " + kind);
    }

    private static float parseFloat(String text) {
        return Float.parseFloat(text);
    }

    private static int parseColor(String text) {
        return (int) Long.parseLong(text);
    }

    private static String formatDayMonth(int month, int day) {
        return String.format(Locale.US, "%02d/%02d", day, month);
    }

    private static final class Primitive {
        final char kind;
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final float radius;
        final int color;
        final float strokeWidth;
        final String text;
        final float textSize;
        final boolean boldText;

        private Primitive(char kind, float x1, float y1, float x2, float y2, float radius,
                          int color, float strokeWidth, String text, float textSize, boolean boldText) {
            this.kind = kind;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.radius = radius;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.text = text;
            this.textSize = textSize;
            this.boldText = boldText;
        }

        static Primitive line(float x1, float y1, float x2, float y2, int color, float strokeWidth) {
            return new Primitive('L', x1, y1, x2, y2, 0f, color, strokeWidth, "", 0f, false);
        }

        static Primitive circle(float x, float y, float radius, int color, float strokeWidth) {
            return new Primitive('C', x, y, 0f, 0f, radius, color, strokeWidth, "", 0f, false);
        }

        static Primitive text(float x, float y, String text, int color, float textSize, boolean boldText) {
            return new Primitive('T', x, y, 0f, 0f, 0f, color, 0f, text, textSize, boldText);
        }
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
