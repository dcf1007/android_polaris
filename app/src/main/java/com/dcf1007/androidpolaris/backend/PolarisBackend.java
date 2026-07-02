package com.dcf1007.androidpolaris.backend;

import com.dcf1007.androidpolaris.astro.AstroMath;
import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Consolidated UI-facing backend for Polaris / HA / RA alignment.
 *
 * <p>This is the only Polaris class that the UI layer imports. It owns input validation, request
 * parsing, readout formatting and the public domain types used by the reticle renderer. The current
 * branch still keeps the low-level astronomical engine internally while the UI no longer depends on
 * separate model, astro or formatting packages.</p>
 */
public final class PolarisBackend {
    public static final double RETICLE_VIEWBOX_WIDTH = PolarisAlignmentCalculator.RETICLE_VIEWBOX_WIDTH;
    public static final double RETICLE_VIEWBOX_HEIGHT = PolarisAlignmentCalculator.RETICLE_VIEWBOX_HEIGHT;
    public static final double RETICLE_CENTER_X = PolarisAlignmentCalculator.RETICLE_CENTER_X;
    public static final double RETICLE_CENTER_Y = PolarisAlignmentCalculator.RETICLE_CENTER_Y;
    public static final double RETICLE_ASPECT_HEIGHT_OVER_WIDTH = RETICLE_VIEWBOX_HEIGHT / RETICLE_VIEWBOX_WIDTH;

    public static final String[] MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };
    public static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private static final SimpleDateFormat LOCAL_DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final PolarisAlignmentCalculator calculator = new PolarisAlignmentCalculator();

    /** Text/UI request for one Polaris calculation pass. */
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

    /** Atmospheric refraction options shown in the Polaris panel. */
    public enum RefractionMode {
        FIXED_BENNETT("Fixed Bennett"),
        SCALED_PRESSURE_TEMPERATURE("Scaled P/T Bennett"),
        ALTITUDE_PRESSURE_TEMPERATURE("Altitude + temp Bennett"),
        OFF("Off");

        private final String displayName;
        RefractionMode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    /** Immutable typed calculation input after UI strings have been validated and parsed. */
    public static final class AlignmentInput {
        public final Date utcInstant;
        public final double latitudeDegrees;
        public final double longitudeDegreesEast;
        public final double targetRightAscensionHours;
        public final int offsetMonth;
        public final int offsetDay;
        public final boolean lockReticleToZeroHourAngle;
        public final RefractionMode refractionMode;
        public final double pressureHpa;
        public final double temperatureCelsius;
        public final double elevationMeters;

        AlignmentInput(Date utcInstant, double latitudeDegrees, double longitudeDegreesEast,
                       double targetRightAscensionHours, int offsetMonth, int offsetDay,
                       boolean lockReticleToZeroHourAngle, RefractionMode refractionMode,
                       double pressureHpa, double temperatureCelsius, double elevationMeters) {
            this.utcInstant = new Date(utcInstant.getTime());
            this.latitudeDegrees = latitudeDegrees;
            this.longitudeDegreesEast = longitudeDegreesEast;
            this.targetRightAscensionHours = targetRightAscensionHours;
            this.offsetMonth = offsetMonth;
            this.offsetDay = offsetDay;
            this.lockReticleToZeroHourAngle = lockReticleToZeroHourAngle;
            this.refractionMode = refractionMode;
            this.pressureHpa = pressureHpa;
            this.temperatureCelsius = temperatureCelsius;
            this.elevationMeters = elevationMeters;
        }
    }

    /** Calculation result in native reticle design-space coordinates. */
    public static final class AlignmentResult {
        public final double julianDateUtc;
        public final double julianDateTt;
        public final double deltaTSeconds;
        public final double localMeanSiderealTimeDegrees;
        public final double localApparentSiderealTimeDegrees;
        public final double calculatedTargetHourAngleDegrees;
        public final double calculatedTargetHourAngleHours;
        public final double activeHourAngleDegrees;
        public final double activeHourAngleHours;
        public final double activeHourAngleDisplayAngleDegrees;
        public final double dateAndPolarReticleRotationDegrees;
        public final int activeOffsetMonth;
        public final int activeOffsetDay;
        public final int zeroHourDateMonth;
        public final int zeroHourDateDay;
        public final double polarisHourAngleHours;
        public final double polarisClockAngleDegrees;
        public final double apparentRightAscensionRadians;
        public final double apparentDeclinationRadians;
        public final double meanRightAscensionRadians;
        public final double meanDeclinationRadians;
        public final double observedRightAscensionRadians;
        public final double observedDeclinationRadians;
        public final double trueAltitudeRadians;
        public final double trueAzimuthRadians;
        public final double refractionArcMinutes;
        public final String refractionDescription;
        public final double markerReticleX;
        public final double markerReticleY;
        public final double radiusReticlePixels;
        public final double nominalRingRadiusReticlePixels;
        public final double pixelPerTangentRadian;
        public final double observedSeparationArcMinutes;
        public final String warningText;

        AlignmentResult(com.dcf1007.androidpolaris.model.AlignmentResult result) {
            this.julianDateUtc = result.julianDateUtc;
            this.julianDateTt = result.julianDateTt;
            this.deltaTSeconds = result.deltaTSeconds;
            this.localMeanSiderealTimeDegrees = result.localMeanSiderealTimeDegrees;
            this.localApparentSiderealTimeDegrees = result.localApparentSiderealTimeDegrees;
            this.calculatedTargetHourAngleDegrees = result.calculatedTargetHourAngleDegrees;
            this.calculatedTargetHourAngleHours = result.calculatedTargetHourAngleHours;
            this.activeHourAngleDegrees = result.activeHourAngleDegrees;
            this.activeHourAngleHours = result.activeHourAngleHours;
            this.activeHourAngleDisplayAngleDegrees = result.activeHourAngleDisplayAngleDegrees;
            this.dateAndPolarReticleRotationDegrees = result.dateAndPolarReticleRotationDegrees;
            this.activeOffsetMonth = result.activeOffsetMonth;
            this.activeOffsetDay = result.activeOffsetDay;
            this.zeroHourDateMonth = result.zeroHourDateMonth;
            this.zeroHourDateDay = result.zeroHourDateDay;
            this.polarisHourAngleHours = result.polarisHourAngleHours;
            this.polarisClockAngleDegrees = result.polarisClockAngleDegrees;
            this.apparentRightAscensionRadians = result.apparentRightAscensionRadians;
            this.apparentDeclinationRadians = result.apparentDeclinationRadians;
            this.meanRightAscensionRadians = result.meanRightAscensionRadians;
            this.meanDeclinationRadians = result.meanDeclinationRadians;
            this.observedRightAscensionRadians = result.observedRightAscensionRadians;
            this.observedDeclinationRadians = result.observedDeclinationRadians;
            this.trueAltitudeRadians = result.trueAltitudeRadians;
            this.trueAzimuthRadians = result.trueAzimuthRadians;
            this.refractionArcMinutes = result.refractionArcMinutes;
            this.refractionDescription = result.refractionDescription;
            this.markerReticleX = result.markerReticleX;
            this.markerReticleY = result.markerReticleY;
            this.radiusReticlePixels = result.radiusReticlePixels;
            this.nominalRingRadiusReticlePixels = result.nominalRingRadiusReticlePixels;
            this.pixelPerTangentRadian = result.pixelPerTangentRadian;
            this.observedSeparationArcMinutes = result.observedSeparationArcMinutes;
            this.warningText = result.warningText == null ? "" : result.warningText;
        }
    }

    public AlignmentResult calculate(Request request) throws ParseException {
        return new AlignmentResult(calculator.calculate(toLegacyInput(toInput(request))));
    }

    public AlignmentInput toInput(Request request) throws ParseException {
        Date selectedDate = parseLocalDateTime(request.localDateTimeText.trim());
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
        builder.append("LAST: ").append(formatHours(result.localApparentSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n");
        builder.append("LMST: ").append(formatHours(result.localMeanSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n");
        builder.append("Target HA: ").append(formatHours(result.activeHourAngleHours))
                .append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")");
        if (input.lockReticleToZeroHourAngle) {
            builder.append(" — locked; live calculated HA ").append(formatHours(result.calculatedTargetHourAngleHours))
                    .append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")");
        }
        builder.append('\n');
        builder.append("0h date label: ").append(String.format(Locale.US, "%02d/%02d", result.zeroHourDateDay, result.zeroHourDateMonth))
                .append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n");
        builder.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n');
        builder.append("Polaris RA/Dec: ").append(formatRightAscension(result.apparentRightAscensionRadians))
                .append(" / ").append(formatDeclination(result.apparentDeclinationRadians)).append('\n');
        builder.append("Polaris clock: ").append(formatHours(result.polarisClockAngleDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n");
        builder.append("Alt/Az: ").append(formatDegrees(result.trueAltitudeRadians, 3))
                .append(" / ").append(formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        builder.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX))
                .append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n');
        builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad", result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n');
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

    public static String formatLocalDateTime(Date date) {
        synchronized (LOCAL_DATE_TIME_FORMAT) { return LOCAL_DATE_TIME_FORMAT.format(date); }
    }

    public static Date parseLocalDateTime(String text) throws ParseException {
        synchronized (LOCAL_DATE_TIME_FORMAT) {
            LOCAL_DATE_TIME_FORMAT.setLenient(false);
            return LOCAL_DATE_TIME_FORMAT.parse(text);
        }
    }

    public static String formatHours(double hours) {
        long totalSeconds = Math.round(normalizeHours(hours) * 3600.0) % 86_400L;
        long hh = totalSeconds / 3600L;
        long mm = (totalSeconds % 3600L) / 60L;
        long ss = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss);
    }

    public static String formatRightAscension(double radians) { return formatHours(AstroMath.radiansToHours(radians)); }

    public static String formatDeclination(double radians) {
        double degrees = radians * AstroMath.RAD_TO_DEG;
        String sign = degrees < 0.0 ? "−" : "+";
        double absoluteDegrees = Math.abs(degrees);
        int wholeDegrees = (int) Math.floor(absoluteDegrees);
        double minutesFloat = (absoluteDegrees - wholeDegrees) * 60.0;
        int minutes = (int) Math.floor(minutesFloat);
        double seconds = (minutesFloat - minutes) * 60.0;
        return String.format(Locale.US, "%s%02d°%02d′%04.1f″", sign, wholeDegrees, minutes, seconds);
    }

    public static String formatDegrees(double radians, int places) {
        return String.format(Locale.US, "%+." + places + "f°", radians * AstroMath.RAD_TO_DEG);
    }

    private com.dcf1007.androidpolaris.model.AlignmentInput toLegacyInput(AlignmentInput input) {
        return new com.dcf1007.androidpolaris.model.AlignmentInput(
                input.utcInstant,
                input.latitudeDegrees,
                input.longitudeDegreesEast,
                input.targetRightAscensionHours,
                input.offsetMonth,
                input.offsetDay,
                input.lockReticleToZeroHourAngle,
                toLegacyRefractionMode(input.refractionMode),
                input.pressureHpa,
                input.temperatureCelsius,
                input.elevationMeters
        );
    }

    private com.dcf1007.androidpolaris.model.RefractionMode toLegacyRefractionMode(RefractionMode mode) {
        RefractionMode safeMode = mode == null ? RefractionMode.FIXED_BENNETT : mode;
        switch (safeMode) {
            case SCALED_PRESSURE_TEMPERATURE: return com.dcf1007.androidpolaris.model.RefractionMode.SCALED_PRESSURE_TEMPERATURE;
            case ALTITUDE_PRESSURE_TEMPERATURE: return com.dcf1007.androidpolaris.model.RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE;
            case OFF: return com.dcf1007.androidpolaris.model.RefractionMode.OFF;
            case FIXED_BENNETT:
            default: return com.dcf1007.androidpolaris.model.RefractionMode.FIXED_BENNETT;
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

    private static double normalizeHours(double hours) {
        double normalized = hours % 24.0;
        return normalized < 0.0 ? normalized + 24.0 : normalized;
    }
}
