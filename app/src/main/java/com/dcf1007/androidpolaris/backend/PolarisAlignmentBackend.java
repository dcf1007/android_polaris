package com.dcf1007.androidpolaris.backend;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;
import com.dcf1007.androidpolaris.util.UiFormatting;

import java.text.ParseException;
import java.util.Date;
import java.util.Locale;

/**
 * Transitional Polaris backend API retained while the UI controller is consolidated around
 * {@link PolarisBackend}. The implementation remains unchanged until the low-level calculator is
 * fully merged into PolarisBackend.
 */
public class PolarisAlignmentBackend {
    public static final double RETICLE_ASPECT_HEIGHT_OVER_WIDTH =
            PolarisAlignmentCalculator.RETICLE_VIEWBOX_HEIGHT / PolarisAlignmentCalculator.RETICLE_VIEWBOX_WIDTH;
    public static final String[] MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };
    public static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private final PolarisAlignmentCalculator calculator = new PolarisAlignmentCalculator();

    public static final class Request {
        public String localDateTimeText;
        public String latitudeText;
        public String longitudeText;
        public String rightAscensionHoursText;
        public String rightAscensionMinutesText;
        public String rightAscensionSecondsText;
        public int offsetMonth;
        public String offsetDayText;
        public boolean lockZeroHourAngle;
        public RefractionMode refractionMode;
        public String pressureText;
        public String temperatureText;
        public String elevationText;
    }

    public AlignmentResult calculate(Request request) throws ParseException {
        return calculator.calculate(toInput(request));
    }

    public AlignmentInput toInput(Request request) throws ParseException {
        Date selectedDate = UiFormatting.parseLocalDateTime(request.localDateTimeText.trim());
        double latitude = parseRequiredDouble(request.latitudeText, "Latitude");
        double longitude = parseRequiredDouble(request.longitudeText, "Longitude");
        double targetRightAscensionHours = request.lockZeroHourAngle ? 0.0 : readRightAscensionHours(request);
        int offsetDay = Math.round((float) parseOptionalDouble(request.offsetDayText, 1.0));
        RefractionMode mode = request.refractionMode == null ? RefractionMode.FIXED_BENNETT : request.refractionMode;
        return new AlignmentInput(
                selectedDate,
                latitude,
                longitude,
                targetRightAscensionHours,
                request.offsetMonth,
                offsetDay,
                request.lockZeroHourAngle,
                mode,
                parseOptionalDouble(request.pressureText, 1013.25),
                parseOptionalDouble(request.temperatureText, 10.0),
                parseOptionalDouble(request.elevationText, 0.0)
        );
    }

    public String formatReadout(AlignmentResult result, AlignmentInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("UTC JD: ").append(String.format(Locale.US, "%.6f", result.julianDateUtc)).append('\n');
        builder.append("LAST: ").append(UiFormatting.formatHours(result.localApparentSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n");
        builder.append("LMST: ").append(UiFormatting.formatHours(result.localMeanSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n");
        builder.append("Target HA: ").append(UiFormatting.formatHours(result.activeHourAngleHours))
                .append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")");
        if (input.lockReticleToZeroHourAngle) {
            builder.append(" — locked; live calculated HA ")
                    .append(UiFormatting.formatHours(result.calculatedTargetHourAngleHours))
                    .append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")");
        }
        builder.append('\n');
        builder.append("0h date label: ").append(String.format(Locale.US, "%02d/%02d", result.zeroHourDateDay, result.zeroHourDateMonth))
                .append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n");
        builder.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n');
        builder.append("Polaris RA/Dec: ").append(UiFormatting.formatRightAscension(result.apparentRightAscensionRadians))
                .append(" / ").append(UiFormatting.formatDeclination(result.apparentDeclinationRadians)).append('\n');
        builder.append("Polaris clock: ").append(UiFormatting.formatHours(result.polarisClockAngleDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n");
        builder.append("Alt/Az: ").append(UiFormatting.formatDegrees(result.trueAltitudeRadians, 3))
                .append(" / ").append(UiFormatting.formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        builder.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX))
                .append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n');
        builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad",
                result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n');
        builder.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds));
        return builder.toString();
    }

    public int clampOffsetDay(int selectedMonth, String rawDayText) {
        if (selectedMonth < 1 || selectedMonth > 12 || rawDayText == null || rawDayText.trim().isEmpty()) return -1;
        try {
            int requested = Math.round(Float.parseFloat(rawDayText.trim()));
            return Math.max(1, Math.min(MONTH_DAYS[selectedMonth - 1], requested));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private double readRightAscensionHours(Request request) {
        double hours = parseOptionalDouble(request.rightAscensionHoursText, Double.NaN);
        double minutes = parseOptionalDouble(request.rightAscensionMinutesText, Double.NaN);
        double seconds = parseOptionalDouble(request.rightAscensionSecondsText, Double.NaN);
        if (!Double.isFinite(hours) || !Double.isFinite(minutes) || !Double.isFinite(seconds)
                || hours < 0.0 || hours >= 24.0
                || minutes < 0.0 || minutes >= 60.0
                || seconds < 0.0 || seconds >= 60.0) {
            throw new IllegalArgumentException("Target RA must be valid hh/mm/ss.");
        }
        return hours + minutes / 60.0 + seconds / 3600.0;
    }

    private static double parseRequiredDouble(String rawText, String fieldName) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) throw new IllegalArgumentException(fieldName + " is required.");
        try { return Double.parseDouble(text); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(fieldName + " must be a valid number."); }
    }

    private static double parseOptionalDouble(String rawText, double fallback) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return fallback;
        try { return Double.parseDouble(text); }
        catch (NumberFormatException exception) { return fallback; }
    }
}
