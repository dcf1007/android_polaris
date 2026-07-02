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
 * <p>This is the single intended Polaris facade. It owns input validation, request parsing,
 * formatting, constants, and the public nested input/result/refraction types. The low-level
 * astronomical engine is still delegated internally until the remaining generated reticle imports
 * are updated away from the previous model package.</p>
 */
public class PolarisBackend extends PolarisAlignmentBackend {
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

    public enum RefractionMode {
        FIXED_BENNETT("Fixed Bennett"),
        SCALED_PRESSURE_TEMPERATURE("Scaled P/T Bennett"),
        ALTITUDE_PRESSURE_TEMPERATURE("Altitude + temp Bennett"),
        OFF("Off");
        private final String displayName;
        RefractionMode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

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

    public static final class AlignmentResult {
        public final double julianDateUtc, julianDateTt, deltaTSeconds;
        public final double localMeanSiderealTimeDegrees, localApparentSiderealTimeDegrees;
        public final double calculatedTargetHourAngleDegrees, calculatedTargetHourAngleHours;
        public final double activeHourAngleDegrees, activeHourAngleHours, activeHourAngleDisplayAngleDegrees;
        public final double dateAndPolarReticleRotationDegrees;
        public final int activeOffsetMonth, activeOffsetDay, zeroHourDateMonth, zeroHourDateDay;
        public final double polarisHourAngleHours, polarisClockAngleDegrees;
        public final double apparentRightAscensionRadians, apparentDeclinationRadians;
        public final double meanRightAscensionRadians, meanDeclinationRadians;
        public final double observedRightAscensionRadians, observedDeclinationRadians;
        public final double trueAltitudeRadians, trueAzimuthRadians;
        public final double refractionArcMinutes;
        public final String refractionDescription;
        public final double markerReticleX, markerReticleY, radiusReticlePixels, nominalRingRadiusReticlePixels;
        public final double pixelPerTangentRadian, observedSeparationArcMinutes;
        public final String warningText;

        AlignmentResult(com.dcf1007.androidpolaris.model.AlignmentResult result) {
            julianDateUtc = result.julianDateUtc;
            julianDateTt = result.julianDateTt;
            deltaTSeconds = result.deltaTSeconds;
            localMeanSiderealTimeDegrees = result.localMeanSiderealTimeDegrees;
            localApparentSiderealTimeDegrees = result.localApparentSiderealTimeDegrees;
            calculatedTargetHourAngleDegrees = result.calculatedTargetHourAngleDegrees;
            calculatedTargetHourAngleHours = result.calculatedTargetHourAngleHours;
            activeHourAngleDegrees = result.activeHourAngleDegrees;
            activeHourAngleHours = result.activeHourAngleHours;
            activeHourAngleDisplayAngleDegrees = result.activeHourAngleDisplayAngleDegrees;
            dateAndPolarReticleRotationDegrees = result.dateAndPolarReticleRotationDegrees;
            activeOffsetMonth = result.activeOffsetMonth;
            activeOffsetDay = result.activeOffsetDay;
            zeroHourDateMonth = result.zeroHourDateMonth;
            zeroHourDateDay = result.zeroHourDateDay;
            polarisHourAngleHours = result.polarisHourAngleHours;
            polarisClockAngleDegrees = result.polarisClockAngleDegrees;
            apparentRightAscensionRadians = result.apparentRightAscensionRadians;
            apparentDeclinationRadians = result.apparentDeclinationRadians;
            meanRightAscensionRadians = result.meanRightAscensionRadians;
            meanDeclinationRadians = result.meanDeclinationRadians;
            observedRightAscensionRadians = result.observedRightAscensionRadians;
            observedDeclinationRadians = result.observedDeclinationRadians;
            trueAltitudeRadians = result.trueAltitudeRadians;
            trueAzimuthRadians = result.trueAzimuthRadians;
            refractionArcMinutes = result.refractionArcMinutes;
            refractionDescription = result.refractionDescription;
            markerReticleX = result.markerReticleX;
            markerReticleY = result.markerReticleY;
            radiusReticlePixels = result.radiusReticlePixels;
            nominalRingRadiusReticlePixels = result.nominalRingRadiusReticlePixels;
            pixelPerTangentRadian = result.pixelPerTangentRadian;
            observedSeparationArcMinutes = result.observedSeparationArcMinutes;
            warningText = result.warningText == null ? "" : result.warningText;
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
        return new AlignmentInput(selectedDate, latitude, longitude, targetRightAscensionHours, request.offsetMonth, offsetDay,
                request.lockZeroHourAngle, mode, parseOptionalDouble(request.pressureText, 1013.25),
                parseOptionalDouble(request.temperatureText, 10.0), parseOptionalDouble(request.elevationText, 0.0));
    }

    public String formatReadout(AlignmentResult result, AlignmentInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("UTC JD: ").append(String.format(Locale.US, "%.6f", result.julianDateUtc)).append('\n');
        builder.append("LAST: ").append(formatHours(result.localApparentSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n");
        builder.append("LMST: ").append(formatHours(result.localMeanSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n");
        builder.append("Target HA: ").append(formatHours(result.activeHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")");
        if (input.lockReticleToZeroHourAngle) builder.append(" — locked; live calculated HA ").append(formatHours(result.calculatedTargetHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")");
        builder.append('\n');
        builder.append("0h date label: ").append(String.format(Locale.US, "%02d/%02d", result.zeroHourDateDay, result.zeroHourDateMonth)).append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n");
        builder.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n');
        builder.append("Polaris RA/Dec: ").append(formatRightAscension(result.apparentRightAscensionRadians)).append(" / ").append(formatDeclination(result.apparentDeclinationRadians)).append('\n');
        builder.append("Polaris clock: ").append(formatHours(result.polarisClockAngleDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n");
        builder.append("Alt/Az: ").append(formatDegrees(result.trueAltitudeRadians, 3)).append(" / ").append(formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        builder.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX)).append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n');
        builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad", result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n');
        builder.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds));
        return builder.toString();
    }

    public int clampOffsetDay(int selectedMonth, String rawDayText) {
        if (selectedMonth < 1 || selectedMonth > 12 || rawDayText == null || rawDayText.trim().isEmpty()) return -1;
        try { return Math.max(1, Math.min(MONTH_DAYS[selectedMonth - 1], Math.round(Float.parseFloat(rawDayText.trim())))); }
        catch (NumberFormatException ignored) { return -1; }
    }

    public static String formatLocalDateTime(Date date) { synchronized (LOCAL_DATE_TIME_FORMAT) { return LOCAL_DATE_TIME_FORMAT.format(date); } }
    public static Date parseLocalDateTime(String text) throws ParseException { synchronized (LOCAL_DATE_TIME_FORMAT) { LOCAL_DATE_TIME_FORMAT.setLenient(false); return LOCAL_DATE_TIME_FORMAT.parse(text); } }
    public static String formatHours(double hours) { long s = Math.round(normalizeHours(hours) * 3600.0) % 86400L; return String.format(Locale.US, "%02d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L); }
    public static String formatRightAscension(double radians) { return formatHours(AstroMath.radiansToHours(radians)); }
    public static String formatDeclination(double radians) { double d = radians * AstroMath.RAD_TO_DEG; String sign = d < 0.0 ? "−" : "+"; double a = Math.abs(d); int deg = (int) Math.floor(a); double mf = (a - deg) * 60.0; int min = (int) Math.floor(mf); double sec = (mf - min) * 60.0; return String.format(Locale.US, "%s%02d°%02d′%04.1f″", sign, deg, min, sec); }
    public static String formatDegrees(double radians, int places) { return String.format(Locale.US, "%+." + places + "f°", radians * AstroMath.RAD_TO_DEG); }

    private com.dcf1007.androidpolaris.model.AlignmentInput toLegacyInput(AlignmentInput input) {
        return new com.dcf1007.androidpolaris.model.AlignmentInput(input.utcInstant, input.latitudeDegrees, input.longitudeDegreesEast,
                input.targetRightAscensionHours, input.offsetMonth, input.offsetDay, input.lockReticleToZeroHourAngle,
                toLegacyRefractionMode(input.refractionMode), input.pressureHpa, input.temperatureCelsius, input.elevationMeters);
    }

    private com.dcf1007.androidpolaris.model.RefractionMode toLegacyRefractionMode(RefractionMode mode) {
        RefractionMode safe = mode == null ? RefractionMode.FIXED_BENNETT : mode;
        switch (safe) {
            case SCALED_PRESSURE_TEMPERATURE: return com.dcf1007.androidpolaris.model.RefractionMode.SCALED_PRESSURE_TEMPERATURE;
            case ALTITUDE_PRESSURE_TEMPERATURE: return com.dcf1007.androidpolaris.model.RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE;
            case OFF: return com.dcf1007.androidpolaris.model.RefractionMode.OFF;
            case FIXED_BENNETT:
            default: return com.dcf1007.androidpolaris.model.RefractionMode.FIXED_BENNETT;
        }
    }

    private double readRightAscensionHours(Request request) {
        double h = parseOptionalDouble(request.rightAscensionHoursText, Double.NaN);
        double m = parseOptionalDouble(request.rightAscensionMinutesText, Double.NaN);
        double s = parseOptionalDouble(request.rightAscensionSecondsText, Double.NaN);
        if (!Double.isFinite(h) || !Double.isFinite(m) || !Double.isFinite(s) || h < 0.0 || h >= 24.0 || m < 0.0 || m >= 60.0 || s < 0.0 || s >= 60.0) throw new IllegalArgumentException("Target RA must be valid hh/mm/ss.");
        return h + m / 60.0 + s / 3600.0;
    }

    private static double parseRequiredDouble(String text, String name) { String value = text == null ? "" : text.trim(); if (value.isEmpty()) throw new IllegalArgumentException(name + " is required."); try { return Double.parseDouble(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be a valid number."); } }
    private static double parseOptionalDouble(String text, double fallback) { String value = text == null ? "" : text.trim(); if (value.isEmpty()) return fallback; try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; } }
    private static double normalizeHours(double hours) { double h = hours % 24.0; return h < 0.0 ? h + 24.0 : h; }
}
