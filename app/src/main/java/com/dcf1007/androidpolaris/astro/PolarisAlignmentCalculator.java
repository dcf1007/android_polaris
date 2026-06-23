package com.dcf1007.androidpolaris.astro;

import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline Polaris alignment and reticle-rotation engine.
 *
 * <p>This class keeps the native app aligned with the sanitized browser model. UTC is used as the
 * offline UT1 proxy for sidereal time, TT is estimated through a built-in ΔT polynomial for
 * apparent-place terms, and all output coordinates remain in the native reticle design-space.</p>
 */
public final class PolarisAlignmentCalculator {
    /** Width of the native reticle design coordinate system. */
    public static final double RETICLE_VIEWBOX_WIDTH = 1501.99;

    /** Height of the native reticle design coordinate system. */
    public static final double RETICLE_VIEWBOX_HEIGHT = 1498.19;

    /** Reticle NCP/epicentre x coordinate in design-space pixels. */
    public static final double RETICLE_CENTER_X = 746.01;

    /** Reticle NCP/epicentre y coordinate in design-space pixels. */
    public static final double RETICLE_CENTER_Y = 746.43;

    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final int DEFAULT_ZERO_HA_MONTH = 10;
    private static final int DEFAULT_ZERO_HA_DAY = 31;
    private static final int DEFAULT_ZERO_HA_ORDINAL = monthDayToOrdinal(DEFAULT_ZERO_HA_MONTH, DEFAULT_ZERO_HA_DAY);

    private static final double POLARIS_RA_J2000_RADIANS = AstroMath.hoursMinutesSecondsToDegrees(2.0, 31.0, 49.09456) * AstroMath.DEG_TO_RAD;
    private static final double POLARIS_DEC_J2000_RADIANS = AstroMath.degreesMinutesSecondsToDegrees(89.0, 15.0, 50.7923) * AstroMath.DEG_TO_RAD;
    private static final double POLARIS_PM_RA_STAR_MAS_PER_YEAR = 44.48;
    private static final double POLARIS_PM_DEC_MAS_PER_YEAR = -11.85;
    private static final double REFERENCE_RING_YEAR = 2020.0;
    private static final double REFERENCE_RING_RADIUS_RETICLE_PX = 342.5;
    private static final double RING_RADIUS_RETICLE_PX_PER_YEAR = -2.0;

    public AlignmentResult calculate(AlignmentInput input) {
        validateInput(input);

        double julianDateUtc = AstroMath.julianDateUtc(input.utcInstant);
        double deltaTSeconds = AstroMath.deltaTSeconds(input.utcInstant);
        double julianDateTt = julianDateUtc + deltaTSeconds / 86_400.0;
        double centuriesTt = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double centuriesUtcAsUt1 = (julianDateUtc - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double decimalYearUtc = AstroMath.decimalYearUtc(input.utcInstant);

        ApparentPolarisPlace apparentPlace = computeApparentPolarisPlace(centuriesTt, julianDateTt);
        double greenwichMeanSiderealTimeDegrees = computeGreenwichMeanSiderealTimeDegrees(julianDateUtc, centuriesUtcAsUt1);
        double equationOfEquinoxesDegrees = apparentPlace.nutation.deltaPsiRadians * Math.cos(apparentPlace.nutation.trueObliquityRadians) * AstroMath.RAD_TO_DEG;
        double localMeanSiderealTimeDegrees = AstroMath.normalizeDegrees(greenwichMeanSiderealTimeDegrees + input.longitudeDegreesEast);
        double localApparentSiderealTimeDegrees = AstroMath.normalizeDegrees(greenwichMeanSiderealTimeDegrees + equationOfEquinoxesDegrees + input.longitudeDegreesEast);

        // Target RA is used only for the visual HA/date-ring state. Polaris placement is physical.
        double calculatedTargetHourAngleDegrees = AstroMath.normalizeDegrees(localApparentSiderealTimeDegrees - input.targetRightAscensionHours * 15.0);
        double calculatedTargetHourAngleHours = calculatedTargetHourAngleDegrees / 15.0;
        double activeHourAngleDegrees = input.lockReticleToZeroHourAngle ? 0.0 : calculatedTargetHourAngleDegrees;
        double activeHourAngleHours = activeHourAngleDegrees / 15.0;
        double activeHourAngleDisplayAngleDegrees = hourAngleDegreesToReticleDisplayAngle(activeHourAngleDegrees);

        int activeOffsetMonth = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_MONTH : input.offsetMonth;
        int activeOffsetDay = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_DAY : input.offsetDay;
        int activeOffsetOrdinal = monthDayToOrdinal(activeOffsetMonth, activeOffsetDay);
        double activeOffsetTemplateAngleDegrees = dateOrdinalToTemplateAngle(activeOffsetOrdinal);
        double dateAndPolarReticleRotationDegrees = AstroMath.normalizeDegrees(activeHourAngleDisplayAngleDegrees - activeOffsetTemplateAngleDegrees);
        MonthDay zeroHourDate = ordinalToMonthDay(normalizeDateOrdinal(activeOffsetOrdinal + activeHourAngleHours / 24.0 * 365.0));

        double polarisHourAngleRadians = AstroMath.normalizeRadians(localApparentSiderealTimeDegrees * AstroMath.DEG_TO_RAD - apparentPlace.apparentRightAscensionRadians);
        double polarisHourAngleHours = AstroMath.radiansToHours(polarisHourAngleRadians);
        HorizontalCoordinates trueHorizontalCoordinates = equatorialToHorizontal(polarisHourAngleRadians, apparentPlace.apparentDeclinationRadians, input.latitudeDegrees * AstroMath.DEG_TO_RAD);
        RefractionCorrection refractionCorrection = computeRefractionCorrection(trueHorizontalCoordinates.altitudeRadians, input);
        double observedDeclinationRadians = apparentPlace.apparentDeclinationRadians + refractionCorrection.radians;

        // Tangent-plane radial scale. The printed year ring supplies the pixel scale; tan(theta)
        // projects the observed polar distance into the native reticle plane.
        double nominalRingRadiusReticlePx = printedYearRingRadiusReticlePx(decimalYearUtc);
        double meanPolarDistanceRadians = Math.PI / 2.0 - apparentPlace.meanDeclinationRadians;
        double observedPolarDistanceRadians = Math.max(0.0, Math.PI / 2.0 - observedDeclinationRadians);
        double pixelPerTangentRadian = nominalRingRadiusReticlePx / Math.tan(meanPolarDistanceRadians);
        double radiusReticlePx = Math.tan(observedPolarDistanceRadians) * pixelPerTangentRadian;

        // Browser/Sky-Watcher reticle convention: Polaris marker angle = 180° - apparent HA.
        double polarisClockAngleDegrees = AstroMath.normalizeDegrees(180.0 - polarisHourAngleHours * 15.0);
        double polarisClockAngleRadians = polarisClockAngleDegrees * AstroMath.DEG_TO_RAD;
        double markerReticleX = RETICLE_CENTER_X + radiusReticlePx * Math.sin(polarisClockAngleRadians);
        double markerReticleY = RETICLE_CENTER_Y - radiusReticlePx * Math.cos(polarisClockAngleRadians);

        List<String> warnings = new ArrayList<>();
        if (input.latitudeDegrees < 0.0) warnings.add("Polaris/NCP alignment is normally not usable from the southern hemisphere.");
        if (trueHorizontalCoordinates.altitudeRadians < 0.0) warnings.add("Polaris is below the mathematical horizon for this site/time.");
        if (decimalYearUtc < 2012.0 || decimalYearUtc > 2032.0) warnings.add("Selected year is outside the printed 2012–2032 ring range; ring scale is extrapolated.");
        if (input.lockReticleToZeroHourAngle) warnings.add("Reticle/date ring locked to 0h; live target HA is still shown in readouts.");

        return new AlignmentResult(
                julianDateUtc,
                julianDateTt,
                deltaTSeconds,
                localMeanSiderealTimeDegrees,
                localApparentSiderealTimeDegrees,
                calculatedTargetHourAngleDegrees,
                calculatedTargetHourAngleHours,
                activeHourAngleDegrees,
                activeHourAngleHours,
                activeHourAngleDisplayAngleDegrees,
                dateAndPolarReticleRotationDegrees,
                activeOffsetMonth,
                activeOffsetDay,
                zeroHourDate.month,
                zeroHourDate.day,
                polarisHourAngleHours,
                polarisClockAngleDegrees,
                apparentPlace.apparentRightAscensionRadians,
                apparentPlace.apparentDeclinationRadians,
                trueHorizontalCoordinates.altitudeRadians,
                trueHorizontalCoordinates.azimuthRadians,
                refractionCorrection.radians * AstroMath.RAD_TO_DEG * 60.0,
                refractionCorrection.description,
                markerReticleX,
                markerReticleY,
                radiusReticlePx,
                nominalRingRadiusReticlePx,
                pixelPerTangentRadian,
                joinWarnings(warnings)
        );
    }

    private static void validateInput(AlignmentInput input) {
        if (input == null) throw new IllegalArgumentException("Alignment input is required.");
        if (input.utcInstant == null) throw new IllegalArgumentException("Date/time is required.");
        if (!Double.isFinite(input.latitudeDegrees) || input.latitudeDegrees < -90.0 || input.latitudeDegrees > 90.0) throw new IllegalArgumentException("Latitude must be between -90° and +90°.");
        if (!Double.isFinite(input.longitudeDegreesEast) || input.longitudeDegreesEast < -180.0 || input.longitudeDegreesEast > 180.0) throw new IllegalArgumentException("Longitude must be between -180° and +180°; east is positive.");
        if (!input.lockReticleToZeroHourAngle && (!Double.isFinite(input.targetRightAscensionHours) || input.targetRightAscensionHours < 0.0 || input.targetRightAscensionHours >= 24.0)) throw new IllegalArgumentException("Target RA must be between 00:00:00 and 23:59:59 unless 0h lock is enabled.");
        if (input.offsetMonth < 1 || input.offsetMonth > 12) throw new IllegalArgumentException("Offset month must be between 1 and 12.");
        if (input.offsetDay < 1 || input.offsetDay > MONTH_DAYS[input.offsetMonth - 1]) throw new IllegalArgumentException("Offset day must be valid for the selected month.");
    }

    private static ApparentPolarisPlace computeApparentPolarisPlace(double centuriesTt, double julianDateTt) {
        double yearsFromJ2000 = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 365.25;
        EquatorialCoordinates properMotionPlace = applyProperMotion(yearsFromJ2000);
        EquatorialCoordinates meanPlace = precessJ2000ToDate(properMotionPlace.rightAscensionRadians, properMotionPlace.declinationRadians, centuriesTt);
        NutationValues nutation = computeCompactNutation(centuriesTt);
        EquatorialCoordinates nutatedPlace = applyNutation(meanPlace.rightAscensionRadians, meanPlace.declinationRadians, nutation);
        EquatorialCoordinates apparentPlace = applyAnnualAberration(nutatedPlace.rightAscensionRadians, nutatedPlace.declinationRadians, centuriesTt, nutation.trueObliquityRadians);
        return new ApparentPolarisPlace(AstroMath.normalizeRadians(apparentPlace.rightAscensionRadians), apparentPlace.declinationRadians, meanPlace.declinationRadians, nutation);
    }

    private static EquatorialCoordinates applyProperMotion(double yearsFromJ2000) {
        double rightAscensionRadians = AstroMath.normalizeRadians(POLARIS_RA_J2000_RADIANS + (POLARIS_PM_RA_STAR_MAS_PER_YEAR / Math.cos(POLARIS_DEC_J2000_RADIANS)) * AstroMath.MILLI_ARCSECOND_TO_RAD * yearsFromJ2000);
        double declinationRadians = POLARIS_DEC_J2000_RADIANS + POLARIS_PM_DEC_MAS_PER_YEAR * AstroMath.MILLI_ARCSECOND_TO_RAD * yearsFromJ2000;
        return new EquatorialCoordinates(rightAscensionRadians, declinationRadians);
    }

    private static EquatorialCoordinates precessJ2000ToDate(double rightAscensionRadians, double declinationRadians, double centuriesTt) {
        double zeta = (2306.2181 * centuriesTt + 0.30188 * centuriesTt * centuriesTt + 0.017998 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;
        double z = (2306.2181 * centuriesTt + 1.09468 * centuriesTt * centuriesTt + 0.018203 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;
        double theta = (2004.3109 * centuriesTt - 0.42665 * centuriesTt * centuriesTt - 0.041833 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;
        double a = Math.cos(declinationRadians) * Math.sin(rightAscensionRadians + zeta);
        double b = Math.cos(theta) * Math.cos(declinationRadians) * Math.cos(rightAscensionRadians + zeta) - Math.sin(theta) * Math.sin(declinationRadians);
        double c = Math.sin(theta) * Math.cos(declinationRadians) * Math.cos(rightAscensionRadians + zeta) + Math.cos(theta) * Math.sin(declinationRadians);
        return new EquatorialCoordinates(AstroMath.normalizeRadians(Math.atan2(a, b) + z), Math.asin(AstroMath.clamp(c, -1.0, 1.0)));
    }

    private static NutationValues computeCompactNutation(double centuriesTt) {
        double sunMeanLongitude = AstroMath.normalizeDegrees(280.4665 + 36000.7698 * centuriesTt) * AstroMath.DEG_TO_RAD;
        double moonMeanLongitude = AstroMath.normalizeDegrees(218.3165 + 481267.8813 * centuriesTt) * AstroMath.DEG_TO_RAD;
        double lunarAscendingNode = AstroMath.normalizeDegrees(125.04452 - 1934.136261 * centuriesTt) * AstroMath.DEG_TO_RAD;
        double deltaPsiArcseconds = -17.20 * Math.sin(lunarAscendingNode) - 1.32 * Math.sin(2.0 * sunMeanLongitude) - 0.23 * Math.sin(2.0 * moonMeanLongitude) + 0.21 * Math.sin(2.0 * lunarAscendingNode);
        double deltaEpsilonArcseconds = 9.20 * Math.cos(lunarAscendingNode) + 0.57 * Math.cos(2.0 * sunMeanLongitude) + 0.10 * Math.cos(2.0 * moonMeanLongitude) - 0.09 * Math.cos(2.0 * lunarAscendingNode);
        double meanObliquityArcseconds = 84381.448 - 46.8150 * centuriesTt - 0.00059 * centuriesTt * centuriesTt + 0.001813 * centuriesTt * centuriesTt * centuriesTt;
        double meanObliquityRadians = meanObliquityArcseconds * AstroMath.ARCSECOND_TO_RAD;
        return new NutationValues(deltaPsiArcseconds * AstroMath.ARCSECOND_TO_RAD, deltaEpsilonArcseconds * AstroMath.ARCSECOND_TO_RAD, meanObliquityRadians, meanObliquityRadians + deltaEpsilonArcseconds * AstroMath.ARCSECOND_TO_RAD);
    }

    private static EquatorialCoordinates applyNutation(double rightAscensionRadians, double declinationRadians, NutationValues nutation) {
        double tangentDeclination = Math.tan(declinationRadians);
        double deltaRightAscension = (Math.cos(nutation.meanObliquityRadians) + Math.sin(nutation.meanObliquityRadians) * Math.sin(rightAscensionRadians) * tangentDeclination) * nutation.deltaPsiRadians - Math.cos(rightAscensionRadians) * tangentDeclination * nutation.deltaEpsilonRadians;
        double deltaDeclination = Math.sin(nutation.meanObliquityRadians) * Math.cos(rightAscensionRadians) * nutation.deltaPsiRadians + Math.sin(rightAscensionRadians) * nutation.deltaEpsilonRadians;
        return new EquatorialCoordinates(AstroMath.normalizeRadians(rightAscensionRadians + deltaRightAscension), declinationRadians + deltaDeclination);
    }

    private static EquatorialCoordinates applyAnnualAberration(double rightAscensionRadians, double declinationRadians, double centuriesTt, double trueObliquityRadians) {
        double apparentSunLongitudeRadians = apparentSunLongitudeRadians(centuriesTt);
        double aberrationConstantRadians = 20.49552 * AstroMath.ARCSECOND_TO_RAD;
        double cosDeclination = Math.cos(declinationRadians);
        double safeCosDeclination = Math.copySign(Math.max(1e-12, Math.abs(cosDeclination)), cosDeclination == 0.0 ? 1.0 : cosDeclination);
        double deltaRightAscension = -aberrationConstantRadians * (Math.cos(rightAscensionRadians) * Math.cos(apparentSunLongitudeRadians) * Math.cos(trueObliquityRadians) + Math.sin(rightAscensionRadians) * Math.sin(apparentSunLongitudeRadians)) / safeCosDeclination;
        double deltaDeclination = -aberrationConstantRadians * (Math.cos(apparentSunLongitudeRadians) * Math.cos(trueObliquityRadians) * (Math.tan(trueObliquityRadians) * Math.cos(declinationRadians) - Math.sin(rightAscensionRadians) * Math.sin(declinationRadians)) + Math.cos(rightAscensionRadians) * Math.sin(declinationRadians) * Math.sin(apparentSunLongitudeRadians));
        return new EquatorialCoordinates(AstroMath.normalizeRadians(rightAscensionRadians + deltaRightAscension), declinationRadians + deltaDeclination);
    }

    private static double apparentSunLongitudeRadians(double centuriesTt) {
        double meanLongitudeDegrees = AstroMath.normalizeDegrees(280.46646 + 36000.76983 * centuriesTt + 0.0003032 * centuriesTt * centuriesTt);
        double meanAnomalyRadians = AstroMath.normalizeDegrees(357.52911 + 35999.05029 * centuriesTt - 0.0001537 * centuriesTt * centuriesTt + centuriesTt * centuriesTt * centuriesTt / 24490000.0) * AstroMath.DEG_TO_RAD;
        double equationOfCenterDegrees = (1.914602 - 0.004817 * centuriesTt - 0.000014 * centuriesTt * centuriesTt) * Math.sin(meanAnomalyRadians) + (0.019993 - 0.000101 * centuriesTt) * Math.sin(2.0 * meanAnomalyRadians) + 0.000289 * Math.sin(3.0 * meanAnomalyRadians);
        double trueLongitudeDegrees = meanLongitudeDegrees + equationOfCenterDegrees;
        double omegaRadians = (125.04 - 1934.136 * centuriesTt) * AstroMath.DEG_TO_RAD;
        return AstroMath.normalizeDegrees(trueLongitudeDegrees - 0.00569 - 0.00478 * Math.sin(omegaRadians)) * AstroMath.DEG_TO_RAD;
    }

    private static double computeGreenwichMeanSiderealTimeDegrees(double julianDateUtc, double centuriesUtcAsUt1) {
        double daysFromJ2000 = julianDateUtc - AstroMath.J2000_JULIAN_DATE;
        return AstroMath.normalizeDegrees(280.46061837 + 360.98564736629 * daysFromJ2000 + 0.000387933 * centuriesUtcAsUt1 * centuriesUtcAsUt1 - centuriesUtcAsUt1 * centuriesUtcAsUt1 * centuriesUtcAsUt1 / 38_710_000.0);
    }

    private static HorizontalCoordinates equatorialToHorizontal(double hourAngleRadians, double declinationRadians, double latitudeRadians) {
        double east = -Math.cos(declinationRadians) * Math.sin(hourAngleRadians);
        double north = Math.sin(declinationRadians) * Math.cos(latitudeRadians) - Math.cos(declinationRadians) * Math.cos(hourAngleRadians) * Math.sin(latitudeRadians);
        double up = Math.sin(declinationRadians) * Math.sin(latitudeRadians) + Math.cos(declinationRadians) * Math.cos(hourAngleRadians) * Math.cos(latitudeRadians);
        double[] normalized = AstroMath.normalizeVector(new double[]{east, north, up});
        return new HorizontalCoordinates(Math.asin(AstroMath.clamp(normalized[2], -1.0, 1.0)), AstroMath.normalizeRadians(Math.atan2(normalized[0], normalized[1])));
    }

    private static RefractionCorrection computeRefractionCorrection(double altitudeRadians, AlignmentInput input) {
        RefractionMode mode = input.refractionMode == null ? RefractionMode.FIXED_BENNETT : input.refractionMode;
        if (mode == RefractionMode.OFF) return new RefractionCorrection(0.0, "off");
        if (altitudeRadians < -AstroMath.DEG_TO_RAD) return new RefractionCorrection(0.0, "below Bennett range");
        double pressureHpa = 1010.0;
        double temperatureCelsius = 10.0;
        String description = "fixed Bennett";
        if (mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE) {
            pressureHpa = input.pressureHpa;
            temperatureCelsius = input.temperatureCelsius;
            description = "scaled P/T Bennett";
        } else if (mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE) {
            pressureHpa = pressureFromElevationMeters(input.elevationMeters);
            temperatureCelsius = input.temperatureCelsius;
            description = "altitude-estimated pressure Bennett";
        }
        double altitudeDegrees = altitudeRadians * AstroMath.RAD_TO_DEG;
        double bennettArcminutes = 1.02 / Math.tan((altitudeDegrees + 10.3 / (altitudeDegrees + 5.11)) * AstroMath.DEG_TO_RAD);
        double scale = (pressureHpa / 1010.0) * (283.0 / (273.0 + temperatureCelsius));
        return new RefractionCorrection((bennettArcminutes * scale / 60.0) * AstroMath.DEG_TO_RAD, description);
    }

    private static double pressureFromElevationMeters(double elevationMeters) {
        double clampedElevation = AstroMath.clamp(elevationMeters, -500.0, 9000.0);
        return 1013.25 * Math.pow(1.0 - 2.25577e-5 * clampedElevation, 5.25588);
    }

    private static double printedYearRingRadiusReticlePx(double decimalYearUtc) {
        return REFERENCE_RING_RADIUS_RETICLE_PX + (decimalYearUtc - REFERENCE_RING_YEAR) * RING_RADIUS_RETICLE_PX_PER_YEAR;
    }

    private static int monthDayToOrdinal(int month, int day) {
        int ordinal = 0;
        for (int index = 0; index < month - 1; index++) ordinal += MONTH_DAYS[index];
        return ordinal + day - 1;
    }

    private static MonthDay ordinalToMonthDay(double ordinalFloat) {
        int wholeDayOrdinal = (int) Math.floor(normalizeDateOrdinal(ordinalFloat) + 1e-12);
        int month = 1;
        while (wholeDayOrdinal >= MONTH_DAYS[month - 1]) {
            wholeDayOrdinal -= MONTH_DAYS[month - 1];
            month++;
        }
        return new MonthDay(month, wholeDayOrdinal + 1);
    }

    private static double dateOrdinalToTemplateAngle(double ordinalFloat) {
        return AstroMath.normalizeDegrees((ordinalFloat - DEFAULT_ZERO_HA_ORDINAL) * 360.0 / 365.0);
    }

    private static double normalizeDateOrdinal(double value) {
        double normalized = value % 365.0;
        return normalized < 0.0 ? normalized + 365.0 : normalized;
    }

    private static double hourAngleDegreesToReticleDisplayAngle(double hourAngleDegrees) {
        return AstroMath.normalizeDegrees(-hourAngleDegrees);
    }

    private static String joinWarnings(List<String> warnings) {
        if (warnings.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String warning : warnings) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(warning);
        }
        return builder.toString();
    }

    private static final class MonthDay {
        final int month;
        final int day;
        MonthDay(int month, int day) { this.month = month; this.day = day; }
    }

    private static final class EquatorialCoordinates {
        final double rightAscensionRadians;
        final double declinationRadians;
        EquatorialCoordinates(double rightAscensionRadians, double declinationRadians) { this.rightAscensionRadians = rightAscensionRadians; this.declinationRadians = declinationRadians; }
    }

    private static final class HorizontalCoordinates {
        final double altitudeRadians;
        final double azimuthRadians;
        HorizontalCoordinates(double altitudeRadians, double azimuthRadians) { this.altitudeRadians = altitudeRadians; this.azimuthRadians = azimuthRadians; }
    }

    private static final class NutationValues {
        final double deltaPsiRadians;
        final double deltaEpsilonRadians;
        final double meanObliquityRadians;
        final double trueObliquityRadians;
        NutationValues(double deltaPsiRadians, double deltaEpsilonRadians, double meanObliquityRadians, double trueObliquityRadians) { this.deltaPsiRadians = deltaPsiRadians; this.deltaEpsilonRadians = deltaEpsilonRadians; this.meanObliquityRadians = meanObliquityRadians; this.trueObliquityRadians = trueObliquityRadians; }
    }

    private static final class ApparentPolarisPlace {
        final double apparentRightAscensionRadians;
        final double apparentDeclinationRadians;
        final double meanDeclinationRadians;
        final NutationValues nutation;
        ApparentPolarisPlace(double apparentRightAscensionRadians, double apparentDeclinationRadians, double meanDeclinationRadians, NutationValues nutation) { this.apparentRightAscensionRadians = apparentRightAscensionRadians; this.apparentDeclinationRadians = apparentDeclinationRadians; this.meanDeclinationRadians = meanDeclinationRadians; this.nutation = nutation; }
    }

    private static final class RefractionCorrection {
        final double radians;
        final String description;
        RefractionCorrection(double radians, String description) { this.radians = radians; this.description = description; }
    }
}
