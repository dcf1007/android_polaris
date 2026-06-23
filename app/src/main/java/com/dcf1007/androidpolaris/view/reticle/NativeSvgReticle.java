package com.dcf1007.androidpolaris.view.reticle;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.PathParser;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.util.UiFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small native object model for the supplied SVG reticle.
 *
 * <p>The model deliberately mirrors the SVG structure: groups are retained by id and each leaf element
 * is represented as a native Canvas drawable. This keeps the overlay independent from WebView and from
 * SVG rendering libraries while still preserving the original SVG coordinate system and grouping logic.</p>
 */
public final class NativeSvgReticle {
    /** Text id used by the latest SVG for the active hour-angle value. */
    public static final String ID_HOUR_ANGLE_VALUE_TEXT = "ha_x2F_value";

    /** Text id used by the latest SVG for the fixed 0h date value. */
    public static final String ID_ZERO_HOUR_DATE_TEXT = "date_x2F_value";

    private NativeSvgReticle() {}

    public interface ReticleNode {
        void draw(Canvas canvas, RenderContext context);
    }

    public static final class Group implements ReticleNode {
        public final String id;
        private final List<ReticleNode> children = new ArrayList<>();

        public Group(String id) {
            this.id = id == null ? "" : id;
        }

        public Group add(ReticleNode child) {
            if (child != null) children.add(child);
            return this;
        }

        public Group group(String groupId) {
            Group child = new Group(groupId);
            add(child);
            return child;
        }

        public Group line(String elementId, float x1, float y1, float x2, float y2, int strokeColor, float strokeWidth, float[] dashPattern) {
            add(new Line(elementId, x1, y1, x2, y2, strokeColor, strokeWidth, dashPattern));
            return this;
        }

        public Group circle(String elementId, float cx, float cy, float radius, int fillColor, int strokeColor, float strokeWidth) {
            add(new Circle(elementId, cx, cy, radius, fillColor, strokeColor, strokeWidth));
            return this;
        }

        public Group path(String elementId, String pathData, int fillColor, int strokeColor, float strokeWidth, float[] dashPattern) {
            add(new SvgPath(elementId, pathData, fillColor, strokeColor, strokeWidth, dashPattern));
            return this;
        }

        public Group text(String elementId, float x, float y, String text, int fillColor, float textSize, boolean bold) {
            add(new Text(elementId, x, y, text, fillColor, textSize, bold));
            return this;
        }

        @Override
        public void draw(Canvas canvas, RenderContext context) {
            canvas.save();
            float rotationDegrees = context.getRotationForGroup(id);
            if (rotationDegrees != 0.0f) {
                canvas.rotate(rotationDegrees,
                        (float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                        (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y);
            }
            String previousGroupId = context.currentGroupId;
            context.currentGroupId = id;
            for (ReticleNode child : children) {
                child.draw(canvas, context);
            }
            context.currentGroupId = previousGroupId;
            canvas.restore();
        }
    }

    private abstract static class Element implements ReticleNode {
        final String id;

        Element(String id) {
            this.id = id == null ? "" : id;
        }
    }

    private static final class Line extends Element {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final int strokeColor;
        final float strokeWidth;
        final float[] dashPattern;

        Line(String id, float x1, float y1, float x2, float y2, int strokeColor, float strokeWidth, float[] dashPattern) {
            super(id);
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            this.dashPattern = dashPattern;
        }

        @Override
        public void draw(Canvas canvas, RenderContext context) {
            if (strokeColor == Color.TRANSPARENT || strokeWidth <= 0.0f) return;
            context.strokePaint.setColor(strokeColor);
            context.strokePaint.setStrokeWidth(strokeWidth);
            context.strokePaint.setStyle(Paint.Style.STROKE);
            context.strokePaint.setStrokeCap(Paint.Cap.BUTT);
            context.strokePaint.setPathEffect(dashPattern == null ? null : new DashPathEffect(dashPattern, 0.0f));

            // The original SVG contains the Polaris ray in the polaris_x5F_indicator group. Keep the
            // group and line object, but drive its endpoint with the live astronomy result.
            if ("polaris_x5F_indicator".equals(context.currentGroupId) && context.result != null) {
                canvas.drawLine((float) PolarisAlignmentCalculator.RETICLE_CENTER_X,
                        (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y,
                        (float) context.result.markerSvgX,
                        (float) context.result.markerSvgY,
                        context.strokePaint);
            } else {
                canvas.drawLine(x1, y1, x2, y2, context.strokePaint);
            }
            context.strokePaint.setPathEffect(null);
        }
    }

    private static final class Circle extends Element {
        final float cx;
        final float cy;
        final float radius;
        final int fillColor;
        final int strokeColor;
        final float strokeWidth;

        Circle(String id, float cx, float cy, float radius, int fillColor, int strokeColor, float strokeWidth) {
            super(id);
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
        }

        @Override
        public void draw(Canvas canvas, RenderContext context) {
            float drawCx = cx;
            float drawCy = cy;
            if ("polaris_x5F_position".equals(id) && context.result != null) {
                drawCx = (float) context.result.markerSvgX;
                drawCy = (float) context.result.markerSvgY;
            }
            if (fillColor != Color.TRANSPARENT) {
                context.fillPaint.setColor(fillColor);
                context.fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(drawCx, drawCy, radius, context.fillPaint);
            }
            if (strokeColor != Color.TRANSPARENT && strokeWidth > 0.0f) {
                context.strokePaint.setColor(strokeColor);
                context.strokePaint.setStrokeWidth(strokeWidth);
                context.strokePaint.setStyle(Paint.Style.STROKE);
                context.strokePaint.setStrokeCap(Paint.Cap.BUTT);
                context.strokePaint.setPathEffect(null);
                canvas.drawCircle(drawCx, drawCy, radius, context.strokePaint);
            }
        }
    }

    private static final class SvgPath extends Element {
        final Path path;
        final int fillColor;
        final int strokeColor;
        final float strokeWidth;
        final float[] dashPattern;

        SvgPath(String id, String pathData, int fillColor, int strokeColor, float strokeWidth, float[] dashPattern) {
            super(id);
            this.path = PathParser.createPathFromPathData(pathData);
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            this.dashPattern = dashPattern;
        }

        @Override
        public void draw(Canvas canvas, RenderContext context) {
            if (fillColor != Color.TRANSPARENT) {
                context.fillPaint.setColor(fillColor);
                context.fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, context.fillPaint);
            }
            if (strokeColor != Color.TRANSPARENT && strokeWidth > 0.0f) {
                context.strokePaint.setColor(strokeColor);
                context.strokePaint.setStrokeWidth(strokeWidth);
                context.strokePaint.setStyle(Paint.Style.STROKE);
                context.strokePaint.setStrokeCap(Paint.Cap.BUTT);
                context.strokePaint.setPathEffect(dashPattern == null ? null : new DashPathEffect(dashPattern, 0.0f));
                canvas.drawPath(path, context.strokePaint);
                context.strokePaint.setPathEffect(null);
            }
        }
    }

    private static final class Text extends Element {
        final float x;
        final float y;
        final String originalText;
        final int fillColor;
        final float textSize;
        final boolean bold;

        Text(String id, float x, float y, String originalText, int fillColor, float textSize, boolean bold) {
            super(id);
            this.x = x;
            this.y = y;
            this.originalText = originalText == null ? "" : originalText;
            this.fillColor = fillColor;
            this.textSize = textSize;
            this.bold = bold;
        }

        @Override
        public void draw(Canvas canvas, RenderContext context) {
            if (fillColor == Color.TRANSPARENT) return;
            context.textPaint.setColor(fillColor);
            context.textPaint.setTextSize(textSize);
            context.textPaint.setFakeBoldText(bold);
            context.textPaint.setTextAlign(Paint.Align.LEFT);
            context.textPaint.setStyle(Paint.Style.FILL);
            canvas.drawText(getRenderedText(context), x, y, context.textPaint);
            context.textPaint.setFakeBoldText(false);
        }

        private String getRenderedText(RenderContext context) {
            if (context.result == null) return originalText;
            if (ID_HOUR_ANGLE_VALUE_TEXT.equals(id)) {
                return UiFormatting.formatHours(context.result.activeHourAngleHours);
            }
            if (ID_ZERO_HOUR_DATE_TEXT.equals(id)) {
                return String.format(Locale.US, "%02d/%02d", context.result.zeroHourDateDay, context.result.zeroHourDateMonth);
            }
            return originalText;
        }
    }

    public static final class RenderContext {
        final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final AlignmentResult result;
        String currentGroupId = "";

        public RenderContext(AlignmentResult result) {
            this.result = result;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.BUTT);
            strokePaint.setStrokeJoin(Paint.Join.MITER);
            fillPaint.setStyle(Paint.Style.FILL);
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            textPaint.setShadowLayer(0.0f, 0.0f, 0.0f, Color.TRANSPARENT);
        }

        float getRotationForGroup(String groupId) {
            if (result == null) return 0.0f;
            if ("Polar_Scope_Circle".equals(groupId) || "Date_Graduation_Circle".equals(groupId)) {
                return (float) result.dateAndPolarReticleRotationDegrees;
            }
            if ("HA_x5F_indicator".equals(groupId)) {
                return (float) result.activeHourAngleDisplayAngleDegrees;
            }
            // The 0h indicator and time scale stay fixed, matching the sanitized HTML behavior.
            return 0.0f;
        }
    }
}
