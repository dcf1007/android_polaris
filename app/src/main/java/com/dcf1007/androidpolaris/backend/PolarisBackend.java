package com.dcf1007.androidpolaris.backend;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Backend for the complete Polaris / HA / RA alignment subsystem. */
public final class PolarisBackend {
    public static final double RETICLE_VIEWBOX_WIDTH = 1501.99;
    public static final double RETICLE_VIEWBOX_HEIGHT = 1498.19;
    public static final double RETICLE_CENTER_X = 746.01;
    public static final double RETICLE_CENTER_Y = 746.43;
    public static final double RETICLE_ASPECT_HEIGHT_OVER_WIDTH = RETICLE_VIEWBOX_HEIGHT / RETICLE_VIEWBOX_WIDTH;
    public static final String[] MONTH_NAMES = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    public static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;
    private static final double MILLIS_PER_DAY = 86_400_000.0;
    private static final double J2000_JULIAN_DATE = 2_451_545.0;
    private static final int DEFAULT_ZERO_HA_MONTH = 10;
    private static final int DEFAULT_ZERO_HA_DAY = 31;
    private static final int DEFAULT_ZERO_HA_ORDINAL = monthDayToOrdinal(DEFAULT_ZERO_HA_MONTH, DEFAULT_ZERO_HA_DAY);
    private static final double POLARIS_RA_J2000_DEGREES = hoursMinutesSecondsToDegrees(2.0, 31.0, 49.09456);
    private static final double POLARIS_DEC_J2000_DEGREES = degreesMinutesSecondsToDegrees(89.0, 15.0, 50.7923);
    private static final double POLARIS_PM_RA_COS_DEC_MAS_PER_YEAR = 44.48;
    private static final double POLARIS_PM_DEC_MAS_PER_YEAR = -11.85;
    private static final SimpleDateFormat LOCAL_DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final YearRadius[] POLARIS_RETICLE_YEAR_RADII = {
            new YearRadius(2012.0, 358.50), new YearRadius(2016.0, 350.50),
            new YearRadius(2020.0, 342.50), new YearRadius(2024.0, 334.75),
            new YearRadius(2028.0, 326.50), new YearRadius(2032.0, 318.50)
    };

    public static final class Request {
        public String localDateTimeText, latitudeText, longitudeText;
        public String rightAscensionHoursText, rightAscensionMinutesText, rightAscensionSecondsText;
        public int offsetMonth;
        public String offsetDayText, pressureText, temperatureText, elevationText;
        public boolean lockZeroHourAngle;
        public RefractionMode refractionMode;
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
        public final double latitudeDegrees, longitudeDegreesEast, targetRightAscensionHours;
        public final int offsetMonth, offsetDay;
        public final boolean lockReticleToZeroHourAngle;
        public final RefractionMode refractionMode;
        public final double pressureHpa, temperatureCelsius, elevationMeters;
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
        AlignmentResult(double julianDateUtc, double julianDateTt, double deltaTSeconds,
                        double localMeanSiderealTimeDegrees, double localApparentSiderealTimeDegrees,
                        double calculatedTargetHourAngleDegrees, double calculatedTargetHourAngleHours,
                        double activeHourAngleDegrees, double activeHourAngleHours,
                        double activeHourAngleDisplayAngleDegrees, double dateAndPolarReticleRotationDegrees,
                        int activeOffsetMonth, int activeOffsetDay, int zeroHourDateMonth, int zeroHourDateDay,
                        double polarisHourAngleHours, double polarisClockAngleDegrees,
                        double apparentRightAscensionRadians, double apparentDeclinationRadians,
                        double meanRightAscensionRadians, double meanDeclinationRadians,
                        double observedRightAscensionRadians, double observedDeclinationRadians,
                        double trueAltitudeRadians, double trueAzimuthRadians, double refractionArcMinutes,
                        String refractionDescription, double markerReticleX, double markerReticleY,
                        double radiusReticlePixels, double nominalRingRadiusReticlePixels,
                        double pixelPerTangentRadian, double observedSeparationArcMinutes, String warningText) {
            this.julianDateUtc = julianDateUtc; this.julianDateTt = julianDateTt; this.deltaTSeconds = deltaTSeconds;
            this.localMeanSiderealTimeDegrees = localMeanSiderealTimeDegrees; this.localApparentSiderealTimeDegrees = localApparentSiderealTimeDegrees;
            this.calculatedTargetHourAngleDegrees = calculatedTargetHourAngleDegrees; this.calculatedTargetHourAngleHours = calculatedTargetHourAngleHours;
            this.activeHourAngleDegrees = activeHourAngleDegrees; this.activeHourAngleHours = activeHourAngleHours; this.activeHourAngleDisplayAngleDegrees = activeHourAngleDisplayAngleDegrees;
            this.dateAndPolarReticleRotationDegrees = dateAndPolarReticleRotationDegrees;
            this.activeOffsetMonth = activeOffsetMonth; this.activeOffsetDay = activeOffsetDay; this.zeroHourDateMonth = zeroHourDateMonth; this.zeroHourDateDay = zeroHourDateDay;
            this.polarisHourAngleHours = polarisHourAngleHours; this.polarisClockAngleDegrees = polarisClockAngleDegrees;
            this.apparentRightAscensionRadians = apparentRightAscensionRadians; this.apparentDeclinationRadians = apparentDeclinationRadians;
            this.meanRightAscensionRadians = meanRightAscensionRadians; this.meanDeclinationRadians = meanDeclinationRadians;
            this.observedRightAscensionRadians = observedRightAscensionRadians; this.observedDeclinationRadians = observedDeclinationRadians;
            this.trueAltitudeRadians = trueAltitudeRadians; this.trueAzimuthRadians = trueAzimuthRadians;
            this.refractionArcMinutes = refractionArcMinutes; this.refractionDescription = refractionDescription;
            this.markerReticleX = markerReticleX; this.markerReticleY = markerReticleY; this.radiusReticlePixels = radiusReticlePixels;
            this.nominalRingRadiusReticlePixels = nominalRingRadiusReticlePixels; this.pixelPerTangentRadian = pixelPerTangentRadian;
            this.observedSeparationArcMinutes = observedSeparationArcMinutes; this.warningText = warningText == null ? "" : warningText;
        }
    }

    public AlignmentInput toInput(Request request) throws ParseException {
        Date selectedDate = parseLocalDateTime(request.localDateTimeText.trim());
        double targetRaHours = request.lockZeroHourAngle ? 0.0 : readRightAscensionHours(request);
        return new AlignmentInput(
                selectedDate,
                parseRequiredDouble(request.latitudeText, "Latitude"),
                parseRequiredDouble(request.longitudeText, "Longitude"),
                targetRaHours,
                request.offsetMonth,
                Math.round((float) parseOptionalDouble(request.offsetDayText, 1.0)),
                request.lockZeroHourAngle,
                request.refractionMode == null ? RefractionMode.FIXED_BENNETT : request.refractionMode,
                parseOptionalDouble(request.pressureText, 1013.25),
                parseOptionalDouble(request.temperatureText, 10.0),
                parseOptionalDouble(request.elevationText, 0.0)
        );
    }

    public AlignmentResult calculate(Request request) throws ParseException { return calculate(toInput(request)); }

    public AlignmentResult calculate(AlignmentInput input) {
        validateInput(input);
        double jdUtc = julianDateUtc(input.utcInstant);
        double deltaTSeconds = deltaTSeconds(input.utcInstant);
        double jdTt = jdUtc + deltaTSeconds / 86400.0;
        double decimalYearUtc = decimalYearUtc(input.utcInstant);
        double gmst = greenwichMeanSiderealTimeDegrees(jdUtc);
        double equationOfEquinoxes = equationOfEquinoxesDegrees(jdTt);
        double lmst = normalizeDegrees(gmst + input.longitudeDegreesEast);
        double last = normalizeDegrees(gmst + equationOfEquinoxes + input.longitudeDegreesEast);
        double calculatedHaDegrees = normalizeDegrees(last - input.targetRightAscensionHours * 15.0);
        double calculatedHaHours = calculatedHaDegrees / 15.0;
        double activeHaDegrees = input.lockReticleToZeroHourAngle ? 0.0 : calculatedHaDegrees;
        double activeHaHours = activeHaDegrees / 15.0;
        double activeHaDisplayDegrees = hourAngleDegreesToReticleDisplayAngle(activeHaDegrees);
        int activeMonth = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_MONTH : input.offsetMonth;
        int activeDay = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_DAY : input.offsetDay;
        int activeOrdinal = monthDayToOrdinal(activeMonth, activeDay);
        double dateRotation = normalizeDegrees(activeHaDisplayDegrees - dateOrdinalToTemplateAngle(activeOrdinal));
        MonthDay zeroDate = ordinalToMonthDay(normalizeDateOrdinal(activeOrdinal + activeHaHours / 24.0 * 365.0));
        PolarisReticlePosition polaris = computeObservedPolarisReticlePosition(input.utcInstant, jdTt, last, input.latitudeDegrees, input.refractionMode, input.pressureHpa, input.temperatureCelsius, input.elevationMeters);
        List<String> warnings = new ArrayList<>();
        if (input.latitudeDegrees < 0.0) warnings.add("Polaris polar alignment is only meaningful from the northern hemisphere.");
        if (polaris.trueAltitudeDegrees < 0.0) warnings.add("Polaris is below the mathematical horizon for this site/time.");
        if (decimalYearUtc < 2012.0 || decimalYearUtc > 2032.0) warnings.add("Selected year is outside the printed 2012–2032 ring range; ring scale is extrapolated.");
        if (input.lockReticleToZeroHourAngle) warnings.add("Reticle/date ring locked to 0h; live target HA is still shown in readouts.");
        return new AlignmentResult(jdUtc, jdTt, deltaTSeconds, lmst, last, calculatedHaDegrees, calculatedHaHours,
                activeHaDegrees, activeHaHours, activeHaDisplayDegrees, dateRotation, activeMonth, activeDay,
                zeroDate.month, zeroDate.day, polaris.apparentHourAngleDegrees / 15.0, polaris.clockAngleDegrees,
                polaris.observedRaDegrees * DEG_TO_RAD, polaris.observedDecDegrees * DEG_TO_RAD,
                polaris.meanRaDegrees * DEG_TO_RAD, polaris.meanDecDegrees * DEG_TO_RAD,
                polaris.observedRaDegrees * DEG_TO_RAD, polaris.observedDecDegrees * DEG_TO_RAD,
                polaris.trueAltitudeDegrees * DEG_TO_RAD, polaris.trueAzimuthDegrees * DEG_TO_RAD,
                polaris.refractionArcMinutes, polaris.refractionDescription, polaris.markerReticleX,
                polaris.markerReticleY, polaris.radiusReticlePx, polaris.nominalRingRadiusReticlePx,
                polaris.pixelPerTangentRadian, polaris.observedSeparationArcMinutes, joinWarnings(warnings));
    }

    public String formatReadout(AlignmentResult result, AlignmentInput input) {
        StringBuilder b = new StringBuilder();
        b.append("UTC JD: ").append(String.format(Locale.US, "%.6f", result.julianDateUtc)).append('\n');
        b.append("LAST: ").append(formatHours(result.localApparentSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n");
        b.append("LMST: ").append(formatHours(result.localMeanSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n");
        b.append("Target HA: ").append(formatHours(result.activeHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")");
        if (input.lockReticleToZeroHourAngle) b.append(" — locked; live calculated HA ").append(formatHours(result.calculatedTargetHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")");
        b.append('\n');
        b.append("0h date label: ").append(String.format(Locale.US, "%02d/%02d", result.zeroHourDateDay, result.zeroHourDateMonth)).append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n");
        b.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n');
        b.append("Polaris RA/Dec: ").append(formatRightAscension(result.apparentRightAscensionRadians)).append(" / ").append(formatDeclination(result.apparentDeclinationRadians)).append('\n');
        b.append("Polaris clock: ").append(formatHours(result.polarisClockAngleDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n");
        b.append("Alt/Az: ").append(formatDegrees(result.trueAltitudeRadians, 3)).append(" / ").append(formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        b.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        b.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX)).append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n');
        b.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad", result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n');
        b.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds));
        return b.toString();
    }

    public int clampOffsetDay(int selectedMonth, String rawDayText) {
        if (selectedMonth < 1 || selectedMonth > 12 || rawDayText == null || rawDayText.trim().isEmpty()) return -1;
        try { return Math.max(1, Math.min(MONTH_DAYS[selectedMonth - 1], Math.round(Float.parseFloat(rawDayText.trim())))); }
        catch (NumberFormatException ignored) { return -1; }
    }

    public static String formatLocalDateTime(Date date) { synchronized (LOCAL_DATE_TIME_FORMAT) { return LOCAL_DATE_TIME_FORMAT.format(date); } }
    public static Date parseLocalDateTime(String text) throws ParseException { synchronized (LOCAL_DATE_TIME_FORMAT) { LOCAL_DATE_TIME_FORMAT.setLenient(false); return LOCAL_DATE_TIME_FORMAT.parse(text); } }
    public static String formatHours(double hours) { long s = Math.round(normalizeHours(hours) * 3600.0) % 86400L; return String.format(Locale.US, "%02d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L); }
    public static String formatRightAscension(double radians) { return formatHours(radiansToHours(radians)); }
    public static String formatDegrees(double radians, int places) { return String.format(Locale.US, "%+." + places + "f°", radians * RAD_TO_DEG); }
    public static String formatDeclination(double radians) { double d = radians * RAD_TO_DEG; String sign = d < 0.0 ? "−" : "+"; double a = Math.abs(d); int deg = (int) Math.floor(a); double mf = (a - deg) * 60.0; int min = (int) Math.floor(mf); double sec = (mf - min) * 60.0; return String.format(Locale.US, "%s%02d°%02d′%04.1f″", sign, deg, min, sec); }

    private static void validateInput(AlignmentInput input) {
        if (input == null) throw new IllegalArgumentException("Alignment input is required.");
        if (input.utcInstant == null) throw new IllegalArgumentException("Date/time is required.");
        if (!Double.isFinite(input.latitudeDegrees) || input.latitudeDegrees < -90.0 || input.latitudeDegrees > 90.0) throw new IllegalArgumentException("Latitude must be between -90° and +90°.");
        if (!Double.isFinite(input.longitudeDegreesEast) || input.longitudeDegreesEast < -180.0 || input.longitudeDegreesEast > 180.0) throw new IllegalArgumentException("Longitude must be between -180° and +180°; east is positive.");
        if (!input.lockReticleToZeroHourAngle && (!Double.isFinite(input.targetRightAscensionHours) || input.targetRightAscensionHours < 0.0 || input.targetRightAscensionHours >= 24.0)) throw new IllegalArgumentException("Target RA must be between 00:00:00 and 23:59:59 unless 0h lock is enabled.");
        if (input.offsetMonth < 1 || input.offsetMonth > 12) throw new IllegalArgumentException("Offset month must be between 1 and 12.");
        if (input.offsetDay < 1 || input.offsetDay > MONTH_DAYS[input.offsetMonth - 1]) throw new IllegalArgumentException("Offset day must be valid for the selected month.");
        RefractionMode mode = input.refractionMode == null ? RefractionMode.FIXED_BENNETT : input.refractionMode;
        if (mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE && (!Double.isFinite(input.pressureHpa) || input.pressureHpa < 300.0 || input.pressureHpa > 1100.0)) throw new IllegalArgumentException("Pressure must be between 300 and 1100 hPa.");
        if ((mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE || mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE) && (!Double.isFinite(input.temperatureCelsius) || input.temperatureCelsius < -80.0 || input.temperatureCelsius > 60.0)) throw new IllegalArgumentException("Temperature must be between −80°C and +60°C.");
        if (mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE && (!Double.isFinite(input.elevationMeters) || input.elevationMeters < -500.0 || input.elevationMeters > 9000.0)) throw new IllegalArgumentException("Altitude must be between −500 m and 9000 m.");
    }

    private static PolarisReticlePosition computeObservedPolarisReticlePosition(Date date, double jdTt, double last, double latitude, RefractionMode refractionMode, double pressure, double temperature, double elevation) {
        double decimalYear = decimalYearUtc(date);
        double yearsFromJ2000 = (jdTt - J2000_JULIAN_DATE) / 365.25;
        EquatorialDegrees properMotion = applyPolarisProperMotion(yearsFromJ2000);
        EquatorialDegrees meanOfDate = precessJ2000EquatorialToDate(properMotion.raDegrees, properMotion.decDegrees, jdTt);
        EquatorialDegrees trueOfDate = applyNutationToEquatorialCoordinates(meanOfDate.raDegrees, meanOfDate.decDegrees, jdTt);
        EquatorialDegrees apparent = applyAnnualAberrationApproximation(trueOfDate.raDegrees, trueOfDate.decDegrees, jdTt);
        ObservedEquatorial observed = applyAtmosphericRefractionCorrection(apparent.raDegrees, apparent.decDegrees, last, latitude, refractionMode, pressure, temperature, elevation);
        double apparentHaDegrees = normalizeDegrees(last - observed.raDegrees);
        double clockAngleDegrees = normalizeDegrees(180.0 - apparentHaDegrees);
        double nominalRadius = interpolateReticleRadiusPx(decimalYear);
        double observedSeparationDegrees = Math.max(0.0, 90.0 - observed.decDegrees);
        double meanSeparationDegrees = Math.max(1e-12, 90.0 - meanOfDate.decDegrees);
        double pixelPerTangentRadian = nominalRadius / Math.tan(meanSeparationDegrees * DEG_TO_RAD);
        double radius = Math.tan(observedSeparationDegrees * DEG_TO_RAD) * pixelPerTangentRadian;
        ReticlePoint point = polarReticlePoint(clockAngleDegrees, radius);
        return new PolarisReticlePosition(meanOfDate.raDegrees, meanOfDate.decDegrees, observed.raDegrees, observed.decDegrees, observed.trueAltitudeDegrees, observed.trueAzimuthDegrees, observed.refractionArcMinutes, observed.refractionDescription, apparentHaDegrees, clockAngleDegrees, observedSeparationDegrees * 60.0, pixelPerTangentRadian, radius, nominalRadius, point.x, point.y);
    }

    private static double greenwichMeanSiderealTimeDegrees(double jdUtc) { double t = (jdUtc - J2000_JULIAN_DATE) / 36525.0; return normalizeDegrees(280.46061837 + 360.98564736629 * (jdUtc - J2000_JULIAN_DATE) + 0.000387933 * t * t - (t * t * t) / 38_710_000.0); }
    private static double equationOfEquinoxesDegrees(double jdTt) { NutationValues n = nutationAndTrueObliquity(jdTt); return n.deltaPsiDegrees * Math.cos(n.trueObliquityDegrees * DEG_TO_RAD); }
    private static NutationValues nutationAndTrueObliquity(double jdTt) { double t = (jdTt - J2000_JULIAN_DATE) / 36525.0; double L = normalizeDegrees(280.4665 + 36000.7698 * t); double l = normalizeDegrees(218.3165 + 481267.8813 * t); double o = normalizeDegrees(125.04452 - 1934.136261 * t + 0.0020708 * t * t + (t * t * t) / 450000.0); double dPsi = (-17.20 * sinDegrees(o) - 1.32 * sinDegrees(2.0 * L) - 0.23 * sinDegrees(2.0 * l) + 0.21 * sinDegrees(2.0 * o)) / 3600.0; double dEps = (9.20 * cosDegrees(o) + 0.57 * cosDegrees(2.0 * L) + 0.10 * cosDegrees(2.0 * l) - 0.09 * cosDegrees(2.0 * o)) / 3600.0; double meanOb = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0; return new NutationValues(dPsi, dEps, meanOb, meanOb + dEps); }
    private static EquatorialDegrees applyPolarisProperMotion(double years) { double ra = POLARIS_RA_J2000_DEGREES * DEG_TO_RAD; double dec = POLARIS_DEC_J2000_DEGREES * DEG_TO_RAD; double masToRad = Math.PI / (180.0 * 3600.0 * 1000.0); double pmRa = POLARIS_PM_RA_COS_DEC_MAS_PER_YEAR * masToRad; double pmDec = POLARIS_PM_DEC_MAS_PER_YEAR * masToRad; double[] pos = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)}; double[] east = {-Math.sin(ra), Math.cos(ra), 0.0}; double[] north = {-Math.cos(ra) * Math.sin(dec), -Math.sin(ra) * Math.sin(dec), Math.cos(dec)}; double[] moved = normalizeVector3(new double[]{pos[0] + years * (pmRa * east[0] + pmDec * north[0]), pos[1] + years * (pmRa * east[1] + pmDec * north[1]), pos[2] + years * (pmRa * east[2] + pmDec * north[2])}); return unitVectorToEquatorial(moved); }
    private static EquatorialDegrees precessJ2000EquatorialToDate(double raDeg, double decDeg, double jdTt) { double t = (jdTt - J2000_JULIAN_DATE) / 36525.0; double zeta = arcsecondsToRadians(2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t); double z = arcsecondsToRadians(2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t); double theta = arcsecondsToRadians(2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t); double ra = raDeg * DEG_TO_RAD; double dec = decDeg * DEG_TO_RAD; double a = Math.cos(dec) * Math.sin(ra + zeta); double b = Math.cos(theta) * Math.cos(dec) * Math.cos(ra + zeta) - Math.sin(theta) * Math.sin(dec); double c = Math.sin(theta) * Math.cos(dec) * Math.cos(ra + zeta) + Math.cos(theta) * Math.sin(dec); return new EquatorialDegrees(normalizeDegrees((Math.atan2(a, b) + z) * RAD_TO_DEG), Math.asin(clamp(c, -1.0, 1.0)) * RAD_TO_DEG); }
    private static EquatorialDegrees applyNutationToEquatorialCoordinates(double raDeg, double decDeg, double jdTt) { NutationValues n = nutationAndTrueObliquity(jdTt); double ra = raDeg * DEG_TO_RAD, dec = decDeg * DEG_TO_RAD; double dPsi = n.deltaPsiDegrees * DEG_TO_RAD, dEps = n.deltaEpsilonDegrees * DEG_TO_RAD, meanOb = n.meanObliquityDegrees * DEG_TO_RAD; double dra = (Math.cos(meanOb) + Math.sin(meanOb) * Math.sin(ra) * Math.tan(dec)) * dPsi - Math.cos(ra) * Math.tan(dec) * dEps; double ddec = Math.sin(meanOb) * Math.cos(ra) * dPsi + Math.sin(ra) * dEps; return new EquatorialDegrees(normalizeDegrees((ra + dra) * RAD_TO_DEG), (dec + ddec) * RAD_TO_DEG); }
    private static EquatorialDegrees applyAnnualAberrationApproximation(double raDeg, double decDeg, double jdTt) { SunLongitudeAndObliquity sun = apparentSunLongitudeAndObliquity(jdTt); double vLong = normalizeDegrees(sun.longitudeDegrees - 90.0) * DEG_TO_RAD; double ob = sun.meanObliquityDegrees * DEG_TO_RAD; double k = arcsecondsToRadians(20.49552); double[] star = equatorialToUnitVector(raDeg, decDeg); double[] velocity = {Math.cos(vLong), Math.sin(vLong) * Math.cos(ob), Math.sin(vLong) * Math.sin(ob)}; double dot = dotProduct3(star, velocity); return unitVectorToEquatorial(normalizeVector3(new double[]{star[0] + k * (velocity[0] - dot * star[0]), star[1] + k * (velocity[1] - dot * star[1]), star[2] + k * (velocity[2] - dot * star[2])})); }
    private static SunLongitudeAndObliquity apparentSunLongitudeAndObliquity(double jdTt) { double t = (jdTt - J2000_JULIAN_DATE) / 36525.0; double meanLong = normalizeDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t); double meanAnom = normalizeDegrees(357.52911 + 35999.05029 * t - 0.0001537 * t * t); double center = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinDegrees(meanAnom) + (0.019993 - 0.000101 * t) * sinDegrees(2.0 * meanAnom) + 0.000289 * sinDegrees(3.0 * meanAnom); double node = 125.04 - 1934.136 * t; double apparentLong = normalizeDegrees(meanLong + center - 0.00569 - 0.00478 * sinDegrees(node)); return new SunLongitudeAndObliquity(apparentLong, nutationAndTrueObliquity(jdTt).meanObliquityDegrees); }
    private static ObservedEquatorial applyAtmosphericRefractionCorrection(double raDeg, double decDeg, double last, double lat, RefractionMode mode, double pressure, double temp, double elevation) { HorizontalDegrees h = equatorialToHorizontal(raDeg, decDeg, last, lat); RefractionCorrection r = bennettRefractionArcminutes(h.altitudeDegrees, mode, pressure, temp, elevation); EquatorialDegrees c = horizontalToEquatorial(h.altitudeDegrees + r.arcminutes / 60.0, h.azimuthDegrees, last, lat); return new ObservedEquatorial(c.raDegrees, c.decDegrees, h.altitudeDegrees, h.azimuthDegrees, r.arcminutes, r.description); }
    private static RefractionCorrection bennettRefractionArcminutes(double alt, RefractionMode inputMode, double inputPressure, double inputTemp, double elevation) { RefractionMode mode = inputMode == null ? RefractionMode.FIXED_BENNETT : inputMode; if (mode == RefractionMode.OFF || alt <= -1.0 || alt >= 89.9) return new RefractionCorrection(0.0, "Refraction off or outside correction altitude range."); double base = 1.02 / Math.tan((alt + 10.3 / (alt + 5.11)) * DEG_TO_RAD); if (mode == RefractionMode.FIXED_BENNETT) return new RefractionCorrection(base, String.format(Locale.US, "Fixed Bennett: %.4f′ at altitude %.3f°.", base, alt)); double pressure = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE ? estimatePressureFromAltitudeHpa(elevation) : inputPressure; double scaled = base * (pressure / 1010.0) * (283.0 / (273.0 + inputTemp)); String source = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE ? String.format(Locale.US, "estimated pressure from %.0f m altitude", elevation) : "entered pressure"; return new RefractionCorrection(scaled, String.format(Locale.US, "Scaled Bennett: %.4f′ at altitude %.3f°, %.1f hPa (%s), %.1f°C.", scaled, alt, pressure, source, inputTemp)); }
    private static double estimatePressureFromAltitudeHpa(double meters) { return 1013.25 * Math.pow(1.0 - 2.25577e-5 * meters, 5.25588); }
    private static HorizontalDegrees equatorialToHorizontal(double raDeg, double decDeg, double last, double latDeg) { double ha = normalizeDegrees(last - raDeg) * DEG_TO_RAD; double dec = decDeg * DEG_TO_RAD; double lat = latDeg * DEG_TO_RAD; double sinAlt = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(ha); double alt = Math.asin(clamp(sinAlt, -1.0, 1.0)); double cosAlt = Math.cos(alt); double sinAz = -Math.sin(ha) * Math.cos(dec) / cosAlt; double cosAz = (Math.sin(dec) - Math.sin(alt) * Math.sin(lat)) / (cosAlt * Math.cos(lat)); return new HorizontalDegrees(alt * RAD_TO_DEG, normalizeDegrees(Math.atan2(sinAz, cosAz) * RAD_TO_DEG)); }
    private static EquatorialDegrees horizontalToEquatorial(double altDeg, double azDeg, double last, double latDeg) { double alt = altDeg * DEG_TO_RAD, az = azDeg * DEG_TO_RAD, lat = latDeg * DEG_TO_RAD; double sinDec = Math.sin(alt) * Math.sin(lat) + Math.cos(alt) * Math.cos(lat) * Math.cos(az); double dec = Math.asin(clamp(sinDec, -1.0, 1.0)); double sinHa = -Math.sin(az) * Math.cos(alt) / Math.cos(dec); double cosHa = (Math.sin(alt) - Math.sin(lat) * Math.sin(dec)) / (Math.cos(lat) * Math.cos(dec)); double ha = normalizeDegrees(Math.atan2(sinHa, cosHa) * RAD_TO_DEG); return new EquatorialDegrees(normalizeDegrees(last - ha), dec * RAD_TO_DEG); }

    private static double interpolateReticleRadiusPx(double year) { if (year <= POLARIS_RETICLE_YEAR_RADII[0].year) return linearInterpolateRadius(POLARIS_RETICLE_YEAR_RADII[0], POLARIS_RETICLE_YEAR_RADII[1], year); int last = POLARIS_RETICLE_YEAR_RADII.length - 1; if (year >= POLARIS_RETICLE_YEAR_RADII[last].year) return linearInterpolateRadius(POLARIS_RETICLE_YEAR_RADII[last - 1], POLARIS_RETICLE_YEAR_RADII[last], year); for (int i = 0; i < last; i++) { YearRadius lo = POLARIS_RETICLE_YEAR_RADII[i], hi = POLARIS_RETICLE_YEAR_RADII[i + 1]; if (year >= lo.year && year <= hi.year) return linearInterpolateRadius(lo, hi, year); } return POLARIS_RETICLE_YEAR_RADII[last].radius; }
    private static double linearInterpolateRadius(YearRadius lo, YearRadius hi, double year) { double f = (year - lo.year) / (hi.year - lo.year); return lo.radius + f * (hi.radius - lo.radius); }
    private static ReticlePoint polarReticlePoint(double angleDeg, double radius) { double a = angleDeg * DEG_TO_RAD; return new ReticlePoint(RETICLE_CENTER_X + radius * Math.sin(a), RETICLE_CENTER_Y - radius * Math.cos(a)); }
    private static int monthDayToOrdinal(int month, int day) { int o = 0; for (int i = 0; i < month - 1; i++) o += MONTH_DAYS[i]; return o + day - 1; }
    private static MonthDay ordinalToMonthDay(double ordinalFloat) { int day = (int) Math.floor(normalizeDateOrdinal(ordinalFloat) + 1e-12); int month = 1; while (day >= MONTH_DAYS[month - 1]) { day -= MONTH_DAYS[month - 1]; month++; } return new MonthDay(month, day + 1); }
    private static double dateOrdinalToTemplateAngle(double ordinal) { return normalizeDegrees((ordinal - DEFAULT_ZERO_HA_ORDINAL) * 360.0 / 365.0); }
    private static double normalizeDateOrdinal(double v) { double n = v % 365.0; return n < 0.0 ? n + 365.0 : n; }
    private static double hourAngleDegreesToReticleDisplayAngle(double ha) { return normalizeDegrees(-ha); }
    private static double[] equatorialToUnitVector(double raDeg, double decDeg) { double ra = raDeg * DEG_TO_RAD, dec = decDeg * DEG_TO_RAD; return new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)}; }
    private static EquatorialDegrees unitVectorToEquatorial(double[] v) { return new EquatorialDegrees(normalizeDegrees(Math.atan2(v[1], v[0]) * RAD_TO_DEG), Math.asin(clamp(v[2], -1.0, 1.0)) * RAD_TO_DEG); }
    private static double[] normalizeVector3(double[] v) { double length = Math.sqrt(dotProduct3(v, v)); return new double[]{v[0] / length, v[1] / length, v[2] / length}; }
    private static double dotProduct3(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double arcsecondsToRadians(double arcseconds) { return (arcseconds / 3600.0) * DEG_TO_RAD; }
    private static double sinDegrees(double degrees) { return Math.sin(degrees * DEG_TO_RAD); }
    private static double cosDegrees(double degrees) { return Math.cos(degrees * DEG_TO_RAD); }
    private static double hoursMinutesSecondsToDegrees(double h, double m, double s) { return 15.0 * (h + m / 60.0 + s / 3600.0); }
    private static double degreesMinutesSecondsToDegrees(double d, double m, double s) { double sign = d < 0.0 ? -1.0 : 1.0; return sign * (Math.abs(d) + m / 60.0 + s / 3600.0); }
    private static double normalizeDegrees(double degrees) { double n = degrees % 360.0; return n < 0.0 ? n + 360.0 : n; }
    private static double normalizeRadians(double radians) { double full = 2.0 * Math.PI; double n = radians % full; return n < 0.0 ? n + full : n; }
    private static double radiansToHours(double radians) { return normalizeRadians(radians) * 12.0 / Math.PI; }
    private static double clamp(double value, double min, double max) { return Math.min(max, Math.max(min, value)); }
    private static double julianDateUtc(Date date) { return date.getTime() / MILLIS_PER_DAY + 2_440_587.5; }
    private static double deltaTSeconds(Date date) { double y = decimalYearUtc(date), t = y - 2000.0; if (y >= 2005.0 && y <= 2050.0) return 62.92 + 0.32217 * t + 0.005589 * t * t; double c = (y - 2000.0) / 100.0; return 64.7 + 64.5 * c + 30.0 * c * c; }
    private static double decimalYearUtc(Date date) { Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")); calendar.setTime(date); int year = calendar.get(Calendar.YEAR); Calendar start = Calendar.getInstance(TimeZone.getTimeZone("UTC")); start.clear(); start.set(Calendar.YEAR, year); start.set(Calendar.MONTH, Calendar.JANUARY); start.set(Calendar.DAY_OF_MONTH, 1); Calendar next = Calendar.getInstance(TimeZone.getTimeZone("UTC")); next.clear(); next.set(Calendar.YEAR, year + 1); next.set(Calendar.MONTH, Calendar.JANUARY); next.set(Calendar.DAY_OF_MONTH, 1); return year + (date.getTime() - start.getTimeInMillis()) / (double) (next.getTimeInMillis() - start.getTimeInMillis()); }
    private static double readRightAscensionHours(Request request) { double h = parseOptionalDouble(request.rightAscensionHoursText, Double.NaN), m = parseOptionalDouble(request.rightAscensionMinutesText, Double.NaN), s = parseOptionalDouble(request.rightAscensionSecondsText, Double.NaN); if (!Double.isFinite(h) || !Double.isFinite(m) || !Double.isFinite(s) || h < 0.0 || h >= 24.0 || m < 0.0 || m >= 60.0 || s < 0.0 || s >= 60.0) throw new IllegalArgumentException("Target RA must be valid hh/mm/ss."); return h + m / 60.0 + s / 3600.0; }
    private static double parseRequiredDouble(String text, String name) { String value = text == null ? "" : text.trim(); if (value.isEmpty()) throw new IllegalArgumentException(name + " is required."); try { return Double.parseDouble(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be a valid number."); } }
    private static double parseOptionalDouble(String text, double fallback) { String value = text == null ? "" : text.trim(); if (value.isEmpty()) return fallback; try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; } }
    private static double normalizeHours(double hours) { double h = hours % 24.0; return h < 0.0 ? h + 24.0 : h; }
    private static String joinWarnings(List<String> warnings) { if (warnings.isEmpty()) return ""; StringBuilder b = new StringBuilder(); for (String warning : warnings) { if (b.length() > 0) b.append(' '); b.append(warning); } return b.toString(); }

    private static final class YearRadius { final double year, radius; YearRadius(double year, double radius) { this.year = year; this.radius = radius; } }
    private static final class MonthDay { final int month, day; MonthDay(int month, int day) { this.month = month; this.day = day; } }
    private static final class ReticlePoint { final double x, y; ReticlePoint(double x, double y) { this.x = x; this.y = y; } }
    private static final class EquatorialDegrees { final double raDegrees, decDegrees; EquatorialDegrees(double raDegrees, double decDegrees) { this.raDegrees = raDegrees; this.decDegrees = decDegrees; } }
    private static final class ObservedEquatorial { final double raDegrees, decDegrees, trueAltitudeDegrees, trueAzimuthDegrees, refractionArcMinutes; final String refractionDescription; ObservedEquatorial(double ra, double dec, double alt, double az, double ref, String desc) { raDegrees = ra; decDegrees = dec; trueAltitudeDegrees = alt; trueAzimuthDegrees = az; refractionArcMinutes = ref; refractionDescription = desc; } }
    private static final class HorizontalDegrees { final double altitudeDegrees, azimuthDegrees; HorizontalDegrees(double alt, double az) { altitudeDegrees = alt; azimuthDegrees = az; } }
    private static final class NutationValues { final double deltaPsiDegrees, deltaEpsilonDegrees, meanObliquityDegrees, trueObliquityDegrees; NutationValues(double dp, double de, double mean, double trueOb) { deltaPsiDegrees = dp; deltaEpsilonDegrees = de; meanObliquityDegrees = mean; trueObliquityDegrees = trueOb; } }
    private static final class SunLongitudeAndObliquity { final double longitudeDegrees, meanObliquityDegrees; SunLongitudeAndObliquity(double lon, double ob) { longitudeDegrees = lon; meanObliquityDegrees = ob; } }
    private static final class RefractionCorrection { final double arcminutes; final String description; RefractionCorrection(double arcminutes, String description) { this.arcminutes = arcminutes; this.description = description; } }
    private static final class PolarisReticlePosition { final double meanRaDegrees, meanDecDegrees, observedRaDegrees, observedDecDegrees, trueAltitudeDegrees, trueAzimuthDegrees, refractionArcMinutes; final String refractionDescription; final double apparentHourAngleDegrees, clockAngleDegrees, observedSeparationArcMinutes, pixelPerTangentRadian, radiusReticlePx, nominalRingRadiusReticlePx, markerReticleX, markerReticleY; PolarisReticlePosition(double meanRa, double meanDec, double obsRa, double obsDec, double alt, double az, double ref, String desc, double ha, double clock, double sep, double pixelScale, double radius, double nominalRadius, double x, double y) { meanRaDegrees = meanRa; meanDecDegrees = meanDec; observedRaDegrees = obsRa; observedDecDegrees = obsDec; trueAltitudeDegrees = alt; trueAzimuthDegrees = az; refractionArcMinutes = ref; refractionDescription = desc; apparentHourAngleDegrees = ha; clockAngleDegrees = clock; observedSeparationArcMinutes = sep; pixelPerTangentRadian = pixelScale; radiusReticlePx = radius; nominalRingRadiusReticlePx = nominalRadius; markerReticleX = x; markerReticleY = y; } }
}
