package com.dcf1007.androidpolaris.view.reticle;

import android.graphics.Color;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;

/**
 * Native reticle geometry reconstructed from the authoritative Star Adventurer SVG.
 *
 * <p>This fallback builder is kept readable and uses native Canvas primitives only. The next generated
 * revision should replace it with a full one-primitive-per-SVG-object import.</p>
 */
public final class NativeReticleGeometry {
    private static final float CX = (float) PolarisAlignmentCalculator.RETICLE_CENTER_X;
    private static final float CY = (float) PolarisAlignmentCalculator.RETICLE_CENTER_Y;

    private static final int RED = Color.rgb(255, 0, 0);
    private static final int WHITE = Color.WHITE;
    private static final int GREY = Color.rgb(127, 127, 127);
    private static final int LIGHT_GREY = Color.rgb(204, 204, 204);
    private static final int DATE_YELLOW = Color.rgb(255, 204, 0);
    private static final int DATE_DIM = Color.rgb(127, 103, 0);
    private static final int DATE_MEDIUM = Color.rgb(204, 165, 0);
    private static final int DATE_RING = Color.rgb(148, 163, 184);
    private static final int NCP_RED = Color.rgb(255, 59, 48);
    private static final int POLARIS_PINK = Color.rgb(255, 128, 128);

    private NativeReticleGeometry() {}

    public static NativeSvgReticle.Group createReticle() {
        NativeSvgReticle.Group root = new NativeSvgReticle.Group("Layer_1");
        buildPolarScopeCircle(root.group("Polar_Scope_Circle"));
        buildDateGraduationCircle(root.group("Date_Graduation_Circle"));
        buildTimeGraduationCircle(root.group("Time_Graduation_Circle"));
        buildHourAngleIndicator(root.group("HA_x5F_indicator"));
        buildZeroHourIndicator(root.group("_x30_h_x5F_indicator"));
        buildPolarisIndicator(root.group("polaris_x5F_indicator"));
        return root;
    }

    private static void buildPolarScopeCircle(NativeSvgReticle.Group polarScope) {
        NativeSvgReticle.Group fiveDegreeDivisions = polarScope.group("_x35_deg_divisions");
        NativeSvgReticle.Group outer = fiveDegreeDivisions.group("outer");
        NativeSvgReticle.Group inner = fiveDegreeDivisions.group("inner");

        for (int angle = 0; angle < 360; angle += 5) {
            boolean cardinalThirty = angle % 30 == 0;
            addRadialLine(outer, angle, 358.5f, cardinalThirty ? 396.5f : 374.5f, RED, 2f);
            addRadialLine(inner, angle, 326.5f, cardinalThirty ? 291.5f : 309.5f, RED, 2f);
        }

        NativeSvgReticle.Group ring2032 = polarScope.group("_x32_032_x5F_ring");
        addFourRingTicks(ring2032, 318.5f, 14.5f, RED);
        polarScope.circle("_x32_028_x5F_ring", CX, CY, 326.5f, Color.TRANSPARENT, RED, 2f);
        NativeSvgReticle.Group ring2024 = polarScope.group("_x32_024_x5F_ring");
        addFourRingTicks(ring2024, 334.75f, 14.5f, RED);
        polarScope.circle("_x32_020_x5F_ring", CX, CY, 342.5f, Color.TRANSPARENT, RED, 2f);
        NativeSvgReticle.Group ring2016 = polarScope.group("_x32_016_x5F_ring");
        addFourRingTicks(ring2016, 350.5f, 14.5f, RED);
        polarScope.circle("_x32_012_x5F_ring", CX, CY, 358.5f, Color.TRANSPARENT, RED, 2f);

        NativeSvgReticle.Group cardinal = polarScope.group("cardinal_x5F_points");
        cardinal.line("", CX, CY - 420f, CX, CY + 420f, RED, 2f, null);
        cardinal.line("", CX - 420f, CY, CX + 420f, CY, RED, 2f, null);
        cardinal.text("", 736.22f, 322.68f, "0", RED, 35f, false);
        cardinal.text("", 1166.72f, 755.68f, "3", RED, 35f, false);
        cardinal.text("", 304.72f, 755.68f, "9", RED, 35f, false);
        cardinal.text("", 736.22f, 1197.68f, "6", RED, 35f, false);
    }

    private static void buildDateGraduationCircle(NativeSvgReticle.Group dateScale) {
        dateScale.circle("", CX, CY, 590.76f, Color.TRANSPARENT, DATE_RING, 2f);
        String[] monthIds = {"_x31_", "_x32_", "_x33_", "_x34_", "_x35_", "_x36_", "_x37_", "_x38_", "_x39_", "_x31_0", "_x31_1", "_x31_2"};
        int[] monthDays = {31,28,31,30,31,30,31,31,30,31,30,31};
        int dayOfYear = 0;
        for (int month = 0; month < 12; month++) {
            NativeSvgReticle.Group monthGroup = dateScale.group(monthIds[month]);
            for (int day = 1; day <= monthDays[month]; day++) {
                float angle = dateOrdinalToTemplateAngle(dayOfYear + day);
                int color = day == 1 || day == monthDays[month] ? DATE_YELLOW : (day % 5 == 0 ? DATE_MEDIUM : DATE_DIM);
                float innerRadius = day == 1 || day == monthDays[month] ? 531.0f : (day % 5 == 0 ? 548.0f : 566.0f);
                float outerRadius = day == 1 || day == monthDays[month] ? 635.0f : (day % 5 == 0 ? 607.0f : 599.0f);
                addRadialLine(monthGroup, angle, innerRadius, outerRadius, color, 2f);
            }
            float labelAngle = dateOrdinalToTemplateAngle(dayOfYear + monthDays[month] / 2f);
            float labelX = CX + (float) Math.sin(Math.toRadians(labelAngle)) * 525f;
            float labelY = CY - (float) Math.cos(Math.toRadians(labelAngle)) * 525f;
            monthGroup.text("", labelX - (month >= 9 ? 18f : 9f), labelY + 12f, String.valueOf(month + 1), DATE_DIM, 35f, false);
            int lastDay = monthDays[month];
            float dayLabelAngle = dateOrdinalToTemplateAngle(dayOfYear + lastDay);
            float dayLabelX = CX + (float) Math.sin(Math.toRadians(dayLabelAngle)) * 502f;
            float dayLabelY = CY - (float) Math.cos(Math.toRadians(dayLabelAngle)) * 502f;
            monthGroup.text("", dayLabelX - 13f, dayLabelY + 8f, String.valueOf(lastDay), DATE_YELLOW, 20f, false);
            dayOfYear += monthDays[month];
        }
    }

    private static void buildTimeGraduationCircle(NativeSvgReticle.Group timeScale) {
        timeScale.circle("", CX, CY, 694.73f, Color.TRANSPARENT, WHITE, 2f);
        for (int tick = 0; tick < 144; tick++) {
            float angle = tick * 2.5f;
            boolean hour = tick % 6 == 0;
            boolean halfHour = tick % 3 == 0;
            int color = hour ? WHITE : (halfHour ? LIGHT_GREY : GREY);
            float innerRadius = hour ? 656.92f : (halfHour ? 675.0f : 677.5f);
            float outerRadius = hour ? 716.0f : (halfHour ? 718.0f : 706.0f);
            addRadialLine(timeScale, angle, innerRadius, outerRadius, color, 2f);
        }
        for (int hour = 0; hour < 24; hour++) {
            float angle = hour * 15f;
            float x = CX + (float) Math.sin(Math.toRadians(angle)) * 744f;
            float y = CY - (float) Math.cos(Math.toRadians(angle)) * 744f;
            String label = hour + "h";
            timeScale.text("", x - (hour < 10 ? 9f : 15f), y + 8f, label, WHITE, 20f, false);
        }
    }

    private static void buildHourAngleIndicator(NativeSvgReticle.Group indicator) {
        indicator.line("", 746.01f, 144.70f, 746.01f, 68.73f, DATE_YELLOW, 2f, null);
        indicator.path("", "M746.01,77.73l-15,28.97h30l-15-28.97Z", DATE_YELLOW, Color.TRANSPARENT, 0f, null);
        indicator.text(NativeSvgReticle.ID_HOUR_ANGLE_VALUE_TEXT, 757.62f, 98.88f, "hh:mm:ss", DATE_YELLOW, 18f, true);
    }

    private static void buildZeroHourIndicator(NativeSvgReticle.Group indicator) {
        indicator.line("", 746.01f, 144.70f, 746.01f, 68.73f, WHITE, 2f, null);
        indicator.path("", "M746.01,135.67l-15-28.97h30l-15,28.97Z", WHITE, Color.TRANSPARENT, 0f, null);
        indicator.text(NativeSvgReticle.ID_ZERO_HOUR_DATE_TEXT, 757.62f, 125.52f, "DD/MM", WHITE, 18f, true);
    }

    private static void buildPolarisIndicator(NativeSvgReticle.Group polaris) {
        polaris.line("", 746.01f, 746.43f, 683.52f, 1069.41f, POLARIS_PINK, 2f, new float[]{7f, 8f});
        polaris.circle("epicentrum_x2F_NCP", CX, CY, 4.5f, NCP_RED, Color.TRANSPARENT, 0f);
        polaris.circle("polaris_x5F_position", 683.52f, 1069.41f, 7.5f, POLARIS_PINK, Color.TRANSPARENT, 0f);
    }

    private static void addFourRingTicks(NativeSvgReticle.Group group, float radius, float halfLength, int color) {
        group.line("", CX - halfLength, CY - radius, CX + halfLength, CY - radius, color, 2f, null);
        group.line("", CX - halfLength, CY + radius, CX + halfLength, CY + radius, color, 2f, null);
        group.line("", CX - radius, CY - halfLength, CX - radius, CY + halfLength, color, 2f, null);
        group.line("", CX + radius, CY - halfLength, CX + radius, CY + halfLength, color, 2f, null);
    }

    private static void addRadialLine(NativeSvgReticle.Group group, float angleDegrees, float radius1, float radius2, int color, float strokeWidth) {
        double radians = Math.toRadians(angleDegrees);
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        group.line("", CX + sin * radius1, CY - cos * radius1, CX + sin * radius2, CY - cos * radius2, color, strokeWidth, null);
    }

    /** Same 365-day template convention used by the sanitized browser page. */
    private static float dateOrdinalToTemplateAngle(float ordinalOneBased) {
        return (float) ((ordinalOneBased - 304.0f) * 360.0f / 365.0f + 90.0f);
    }
}
