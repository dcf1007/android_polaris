package com.dcf1007.androidpolaris.astro;

import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline Polaris alignment and reticle-rotation engine.
 *
 * <p>This class is a direct native-port of the sanitized HTML alignment backend. The model is
 * deliberately kept explicit and offline: UTC from the device is used as the UT1 proxy for
 * sidereal time, TT is estimated with the same built-in ΔT polynomial for slowly varying
 * apparent-place terms, and all marker coordinates are returned in native reticle design-space.</p>
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

    /** Polaris J2000/ICRS catalogue values used by the HTML apparent-place pipeline. */
    private static final double POLARIS_RA_J2000_DEGREES =
            AstroMath.hoursMinutesSecondsToDegrees(2.0, 31.0, 49.09456);
    private static final double POLARIS_DEC_J2000_DEGREES =
            AstroMath.degreesMinutesSecondsToDegrees(89.0, 15.0, 50.7923);

    /** Proper motion in RA is μ_α* = μ_α cosδ, in milliarcseconds/year. */
    private static final double POLARIS_PM_RA_COS_DEC_MAS_PER_YEAR = 44.48;
    private static final double POLARIS_PM_DEC_MAS_PER_YEAR = -11.85;

    /** Printed Polaris year-ring radii measured from the source reticle artwork, in pixels. */
    private static final YearRadius[] POLARIS_RETICLE_YEAR_RADII = {
            new YearRadius(2012.0, 358.50),
            new YearRadius(2016.0, 350.50),
            new YearRadius(2020.0, 342.50),
            new YearRadius(2024.0, 334.75),
            new YearRadius(2028.0, 326.50),
            new YearRadius(2032.0, 318.50)
    };

    public AlignmentResult calculate(AlignmentInput input) {
        validateInput(input);

        double julianDateUtc = AstroMath.julianDateUtc(input.utcInstant);
        double deltaTSeconds = AstroMath.deltaTSeconds(input.utcInstant);
        double julianDateTt = julianDateUtc + deltaTSeconds / 86_400.0;
        double decimalYearUtc = AstroMath.decimalYearUtc(input.utcInstant);

        double greenwichMeanSiderealDegrees = greenwichMeanSiderealTimeDegrees(julianDateUtc);
        double equationOfEquinoxesDegrees = equationOfEquinoxesDegrees(julianDateTt);
        double localMeanSiderealDegrees =
                AstroMath.normalizeDegrees(greenwichMeanSiderealDegrees + input.longitudeDegreesEast);
        double localApparentSiderealDegrees =
                AstroMath.normalizeDegrees(greenwichMeanSiderealDegrees + equationOfEquinoxesDegrees + input.longitudeDegreesEast);

        double calculatedTargetHaDegrees =
                AstroMath.normalizeDegrees(localApparentSiderealDegrees - input.targetRightAscensionHours * 15.0);
        double calculatedTargetHaHours = calculatedTargetHaDegrees / 15.0;
        double activeHaDegrees = input.lockReticleToZeroHourAngle ? 0.0 : calculatedTargetHaDegrees;
        double activeHaHours = activeHaDegrees / 15.0;
        double activeHaDisplayAngleDegrees = hourAngleDegreesToReticleDisplayAngle(activeHaDegrees);

        int activeOffsetMonth = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_MONTH : input.offsetMonth;
        int activeOffsetDay = input.lockReticleToZeroHourAngle ? DEFAULT_ZERO_HA_DAY : input.offsetDay;
        int activeOffsetOrdinal = monthDayToOrdinal(activeOffsetMonth, activeOffsetDay);
        double activeOffsetUnrotatedAngleDegrees = dateOrdinalToTemplateAngle(activeOffsetOrdinal);
        double dateAndPolarReticleRotationDegrees =
                AstroMath.normalizeDegrees(activeHaDisplayAngleDegrees - activeOffsetUnrotatedAngleDegrees);

        MonthDay zeroHourDate = ordinalToMonthDay(
                normalizeDateOrdinal(activeOffsetOrdinal + activeHaHours / 24.0 * 365.0));

        PolarisReticlePosition polaris = computeObservedPolarisReticlePosition(
                input.utcInstant,
                julianDateTt,
                localApparentSiderealDegrees,
                input.latitudeDegrees,
                input.refractionMode,
                input.pressureHpa,
                input.temperatureCelsius,
                input.elevationMeters
        );

        List<String> warnings = new ArrayList<>();
        if (input.latitudeDegrees < 0.0) {
            warnings.add("Polaris polar alignment is only meaningful from the northern hemisphere.");
        }
        if (polaris.trueAltitudeDegrees < 0.0) {
            warnings.add("Polaris is below the mathematical horizon for this site/time.");
        }
        if (decimalYearUtc < 2012.0 || decimalYearUtc > 2032.0) {
            warnings.add("Selected year is outside the printed 2012–2032 ring range; ring scale is extrapolated.");
        }
        if (input.lockReticleToZeroHourAngle) {
            warnings.add("Reticle/date ring locked to 0h; live target HA is still shown in readouts.");
        }

        return new AlignmentResult(
                julianDateUtc,
                julianDateTt,
                deltaTSeconds,
                localMeanSiderealDegrees,
                localApparentSiderealDegrees,
                calculatedTargetHaDegrees,
                calculatedTargetHaHours,
                activeHaDegrees,
                activeHaHours,
                activeHaDisplayAngleDegrees,
                dateAndPolarReticleRotationDegrees,
                activeOffsetMonth,
                activeOffsetDay,
                zeroHourDate.month,
                zeroHourDate.day,
                polaris.apparentHourAngleDegrees / 15.0,
                polaris.clockAngleDegrees,
                polaris.observedRaDegrees * AstroMath.DEG_TO_RAD,
                polaris.observedDecDegrees * AstroMath.DEG_TO_RAD,
                polaris.meanRaDegrees * AstroMath.DEG_TO_RAD,
                polaris.meanDecDegrees * AstroMath.DEG_TO_RAD,
                polaris.observedRaDegrees * AstroMath.DEG_TO_RAD,
                polaris.observedDecDegrees * AstroMath.DEG_TO_RAD,
                polaris.trueAltitudeDegrees * AstroMath.DEG_TO_RAD,
                polaris.trueAzimuthDegrees * AstroMath.DEG_TO_RAD,
                polaris.refractionArcMinutes,
                polaris.refractionDescription,
                polaris.markerReticleX,
                polaris.markerReticleY,
                polaris.radiusReticlePx,
                polaris.nominalRingRadiusReticlePx,
                polaris.pixelPerTangentRadian,
                polaris.observedSeparationArcMinutes,
                joinWarnings(warnings)
        );
    }

    private static void validateInput(AlignmentInput input) {
        if (input == null) throw new IllegalArgumentException("Alignment input is required.");
        if (input.utcInstant == null) throw new IllegalArgumentException("Date/time is required.");
        if (!Double.isFinite(input.latitudeDegrees) || input.latitudeDegrees < -90.0 || input.latitudeDegrees > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90° and +90°.");
        }
        if (!Double.isFinite(input.longitudeDegreesEast) || input.longitudeDegreesEast < -180.0 || input.longitudeDegreesEast > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180° and +180°; east is positive.");
        }
        if (!input.lockReticleToZeroHourAngle
                && (!Double.isFinite(input.targetRightAscensionHours)
                || input.targetRightAscensionHours < 0.0
                || input.targetRightAscensionHours >= 24.0)) {
            throw new IllegalArgumentException("Target RA must be between 00:00:00 and 23:59:59 unless 0h lock is enabled.");
        }
        if (input.offsetMonth < 1 || input.offsetMonth > 12) {
            throw new IllegalArgumentException("Offset month must be between 1 and 12.");
        }
        if (input.offsetDay < 1 || input.offsetDay > MONTH_DAYS[input.offsetMonth - 1]) {
            throw new IllegalArgumentException("Offset day must be valid for the selected month.");
        }
        RefractionMode mode = input.refractionMode == null ? RefractionMode.FIXED_BENNETT : input.refractionMode;
        if (mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE
                && (!Double.isFinite(input.pressureHpa) || input.pressureHpa < 300.0 || input.pressureHpa > 1100.0)) {
            throw new IllegalArgumentException("Pressure must be between 300 and 1100 hPa.");
        }
        if ((mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE || mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE)
                && (!Double.isFinite(input.temperatureCelsius) || input.temperatureCelsius < -80.0 || input.temperatureCelsius > 60.0)) {
            throw new IllegalArgumentException("Temperature must be between −80°C and +60°C.");
        }
        if (mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE
                && (!Double.isFinite(input.elevationMeters) || input.elevationMeters < -500.0 || input.elevationMeters > 9000.0)) {
            throw new IllegalArgumentException("Altitude must be between −500 m and 9000 m.");
        }
    }

    private static PolarisReticlePosition computeObservedPolarisReticlePosition(
            java.util.Date selectedDate,
            double julianDateTt,
            double localApparentSiderealDegrees,
            double observerLatitudeDegrees,
            RefractionMode refractionMode,
            double pressureHpa,
            double temperatureCelsius,
            double elevationMeters
    ) {
        double decimalYear = AstroMath.decimalYearUtc(selectedDate);
        double yearsFromJ2000 = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 365.25;

        EquatorialDegrees properMotionAdjusted = applyPolarisProperMotion(yearsFromJ2000);
        EquatorialDegrees meanOfDate = precessJ2000EquatorialToDate(
                properMotionAdjusted.raDegrees, properMotionAdjusted.decDegrees, julianDateTt);
        EquatorialDegrees trueOfDate = applyNutationToEquatorialCoordinates(
                meanOfDate.raDegrees, meanOfDate.decDegrees, julianDateTt);
        EquatorialDegrees apparent = applyAnnualAberrationApproximation(
                trueOfDate.raDegrees, trueOfDate.decDegrees, julianDateTt);
        ObservedEquatorial observed = applyAtmosphericRefractionCorrection(
                apparent.raDegrees,
                apparent.decDegrees,
                localApparentSiderealDegrees,
                observerLatitudeDegrees,
                refractionMode,
                pressureHpa,
                temperatureCelsius,
                elevationMeters
        );

        double apparentHaDegrees = AstroMath.normalizeDegrees(localApparentSiderealDegrees - observed.raDegrees);
        double clockAngleDegrees = AstroMath.normalizeDegrees(180.0 - apparentHaDegrees);

        double nominalYearRingRadiusPx = interpolateReticleRadiusPx(decimalYear);
        double observedPoleSeparationDegrees = Math.max(0.0, 90.0 - observed.decDegrees);
        double meanPoleSeparationDegrees = Math.max(1e-12, 90.0 - meanOfDate.decDegrees);
        double pixelPerTangentRadian = nominalYearRingRadiusPx / Math.tan(meanPoleSeparationDegrees * AstroMath.DEG_TO_RAD);
        double radiusReticlePx = Math.tan(observedPoleSeparationDegrees * AstroMath.DEG_TO_RAD) * pixelPerTangentRadian;
        ReticlePoint point = polarReticlePoint(clockAngleDegrees, radiusReticlePx);

        return new PolarisReticlePosition(
                meanOfDate.raDegrees,
                meanOfDate.decDegrees,
                observed.raDegrees,
                observed.decDegrees,
                observed.trueAltitudeDegrees,
                observed.trueAzimuthDegrees,
                observed.refractionArcMinutes,
                observed.refractionDescription,
                apparentHaDegrees,
                clockAngleDegrees,
                observedPoleSeparationDegrees * 60.0,
                pixelPerTangentRadian,
                radiusReticlePx,
                nominalYearRingRadiusPx,
                point.x,
                point.y
        );
    }

    private static double greenwichMeanSiderealTimeDegrees(double julianDateUtc) {
        double julianCenturiesUtc = (julianDateUtc - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        return AstroMath.normalizeDegrees(
                280.46061837
                        + 360.98564736629 * (julianDateUtc - AstroMath.J2000_JULIAN_DATE)
                        + 0.000387933 * julianCenturiesUtc * julianCenturiesUtc
                        - (julianCenturiesUtc * julianCenturiesUtc * julianCenturiesUtc) / 38_710_000.0
        );
    }

    private static double equationOfEquinoxesDegrees(double julianDateTt) {
        NutationValues nutation = nutationAndTrueObliquity(julianDateTt);
        return nutation.deltaPsiDegrees * Math.cos(nutation.trueObliquityDegrees * AstroMath.DEG_TO_RAD);
    }

    private static NutationValues nutationAndTrueObliquity(double julianDateTt) {
        double t = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 36525.0;

        double sunMeanLongitudeDegrees = AstroMath.normalizeDegrees(280.4665 + 36000.7698 * t);
        double moonMeanLongitudeDegrees = AstroMath.normalizeDegrees(218.3165 + 481267.8813 * t);
        double lunarAscendingNodeDegrees = AstroMath.normalizeDegrees(
                125.04452 - 1934.136261 * t
                        + 0.0020708 * t * t
                        + (t * t * t) / 450000.0
        );

        double deltaPsiArcseconds =
                -17.20 * sinDegrees(lunarAscendingNodeDegrees)
                        - 1.32 * sinDegrees(2.0 * sunMeanLongitudeDegrees)
                        - 0.23 * sinDegrees(2.0 * moonMeanLongitudeDegrees)
                        + 0.21 * sinDegrees(2.0 * lunarAscendingNodeDegrees);

        double deltaEpsilonArcseconds =
                9.20 * cosDegrees(lunarAscendingNodeDegrees)
                        + 0.57 * cosDegrees(2.0 * sunMeanLongitudeDegrees)
                        + 0.10 * cosDegrees(2.0 * moonMeanLongitudeDegrees)
                        - 0.09 * cosDegrees(2.0 * lunarAscendingNodeDegrees);

        double meanObliquityDegrees = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0
                - (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0;

        double deltaPsiDegrees = deltaPsiArcseconds / 3600.0;
        double deltaEpsilonDegrees = deltaEpsilonArcseconds / 3600.0;

        return new NutationValues(
                deltaPsiDegrees,
                deltaEpsilonDegrees,
                meanObliquityDegrees,
                meanObliquityDegrees + deltaEpsilonDegrees
        );
    }

    private static EquatorialDegrees applyPolarisProperMotion(double yearsFromJ2000) {
        double raRadians = POLARIS_RA_J2000_DEGREES * AstroMath.DEG_TO_RAD;
        double decRadians = POLARIS_DEC_J2000_DEGREES * AstroMath.DEG_TO_RAD;
        double masToRadians = Math.PI / (180.0 * 3600.0 * 1000.0);
        double properMotionRaCosDecRadiansPerYear = POLARIS_PM_RA_COS_DEC_MAS_PER_YEAR * masToRadians;
        double properMotionDecRadiansPerYear = POLARIS_PM_DEC_MAS_PER_YEAR * masToRadians;

        double[] positionVector = {
                Math.cos(decRadians) * Math.cos(raRadians),
                Math.cos(decRadians) * Math.sin(raRadians),
                Math.sin(decRadians)
        };
        double[] eastTangent = {-Math.sin(raRadians), Math.cos(raRadians), 0.0};
        double[] northTangent = {
                -Math.cos(raRadians) * Math.sin(decRadians),
                -Math.sin(raRadians) * Math.sin(decRadians),
                Math.cos(decRadians)
        };

        double[] movedVector = normalizeVector3(new double[]{
                positionVector[0] + yearsFromJ2000 * (properMotionRaCosDecRadiansPerYear * eastTangent[0] + properMotionDecRadiansPerYear * northTangent[0]),
                positionVector[1] + yearsFromJ2000 * (properMotionRaCosDecRadiansPerYear * eastTangent[1] + properMotionDecRadiansPerYear * northTangent[1]),
                positionVector[2] + yearsFromJ2000 * (properMotionRaCosDecRadiansPerYear * eastTangent[2] + properMotionDecRadiansPerYear * northTangent[2])
        });

        return unitVectorToEquatorial(movedVector);
    }

    private static EquatorialDegrees precessJ2000EquatorialToDate(double raDegrees, double decDegrees, double julianDateTt) {
        double t = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double zetaRadians = arcsecondsToRadians(2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t);
        double zRadians = arcsecondsToRadians(2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t);
        double thetaRadians = arcsecondsToRadians(2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t);

        double raRadians = raDegrees * AstroMath.DEG_TO_RAD;
        double decRadians = decDegrees * AstroMath.DEG_TO_RAD;
        double a = Math.cos(decRadians) * Math.sin(raRadians + zetaRadians);
        double b = Math.cos(thetaRadians) * Math.cos(decRadians) * Math.cos(raRadians + zetaRadians)
                - Math.sin(thetaRadians) * Math.sin(decRadians);
        double c = Math.sin(thetaRadians) * Math.cos(decRadians) * Math.cos(raRadians + zetaRadians)
                + Math.cos(thetaRadians) * Math.sin(decRadians);

        return new EquatorialDegrees(
                AstroMath.normalizeDegrees((Math.atan2(a, b) + zRadians) * AstroMath.RAD_TO_DEG),
                Math.asin(AstroMath.clamp(c, -1.0, 1.0)) * AstroMath.RAD_TO_DEG
        );
    }

    private static EquatorialDegrees applyNutationToEquatorialCoordinates(double raDegrees, double decDegrees, double julianDateTt) {
        NutationValues nutation = nutationAndTrueObliquity(julianDateTt);
        double raRadians = raDegrees * AstroMath.DEG_TO_RAD;
        double decRadians = decDegrees * AstroMath.DEG_TO_RAD;
        double deltaPsiRadians = nutation.deltaPsiDegrees * AstroMath.DEG_TO_RAD;
        double deltaEpsilonRadians = nutation.deltaEpsilonDegrees * AstroMath.DEG_TO_RAD;
        double meanObliquityRadians = nutation.meanObliquityDegrees * AstroMath.DEG_TO_RAD;

        double deltaRaRadians =
                (Math.cos(meanObliquityRadians)
                        + Math.sin(meanObliquityRadians) * Math.sin(raRadians) * Math.tan(decRadians)) * deltaPsiRadians
                        - Math.cos(raRadians) * Math.tan(decRadians) * deltaEpsilonRadians;
        double deltaDecRadians =
                Math.sin(meanObliquityRadians) * Math.cos(raRadians) * deltaPsiRadians
                        + Math.sin(raRadians) * deltaEpsilonRadians;

        return new EquatorialDegrees(
                AstroMath.normalizeDegrees((raRadians + deltaRaRadians) * AstroMath.RAD_TO_DEG),
                (decRadians + deltaDecRadians) * AstroMath.RAD_TO_DEG
        );
    }

    private static EquatorialDegrees applyAnnualAberrationApproximation(double raDegrees, double decDegrees, double julianDateTt) {
        SunLongitudeAndObliquity sun = apparentSunLongitudeAndObliquity(julianDateTt);
        double earthVelocityLongitudeDegrees = AstroMath.normalizeDegrees(sun.longitudeDegrees - 90.0);
        double earthVelocityLongitudeRadians = earthVelocityLongitudeDegrees * AstroMath.DEG_TO_RAD;
        double obliquityRadians = sun.meanObliquityDegrees * AstroMath.DEG_TO_RAD;
        double aberrationConstantRadians = arcsecondsToRadians(20.49552);

        double[] starVector = equatorialToUnitVector(raDegrees, decDegrees);
        double[] velocityVector = {
                Math.cos(earthVelocityLongitudeRadians),
                Math.sin(earthVelocityLongitudeRadians) * Math.cos(obliquityRadians),
                Math.sin(earthVelocityLongitudeRadians) * Math.sin(obliquityRadians)
        };
        double starDotVelocity = dotProduct3(starVector, velocityVector);
        double[] apparentVector = normalizeVector3(new double[]{
                starVector[0] + aberrationConstantRadians * (velocityVector[0] - starDotVelocity * starVector[0]),
                starVector[1] + aberrationConstantRadians * (velocityVector[1] - starDotVelocity * starVector[1]),
                starVector[2] + aberrationConstantRadians * (velocityVector[2] - starDotVelocity * starVector[2])
        });

        return unitVectorToEquatorial(apparentVector);
    }

    private static SunLongitudeAndObliquity apparentSunLongitudeAndObliquity(double julianDateTt) {
        double t = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double meanLongitudeDegrees = AstroMath.normalizeDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t);
        double meanAnomalyDegrees = AstroMath.normalizeDegrees(357.52911 + 35999.05029 * t - 0.0001537 * t * t);
        double equationOfCenterDegrees =
                (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinDegrees(meanAnomalyDegrees)
                        + (0.019993 - 0.000101 * t) * sinDegrees(2.0 * meanAnomalyDegrees)
                        + 0.000289 * sinDegrees(3.0 * meanAnomalyDegrees);
        double lunarNodeDegrees = 125.04 - 1934.136 * t;
        double trueLongitudeDegrees = meanLongitudeDegrees + equationOfCenterDegrees;
        double apparentLongitudeDegrees = AstroMath.normalizeDegrees(
                trueLongitudeDegrees - 0.00569 - 0.00478 * sinDegrees(lunarNodeDegrees));
        NutationValues nutation = nutationAndTrueObliquity(julianDateTt);

        return new SunLongitudeAndObliquity(apparentLongitudeDegrees, nutation.meanObliquityDegrees);
    }

    private static ObservedEquatorial applyAtmosphericRefractionCorrection(
            double raDegrees,
            double decDegrees,
            double localApparentSiderealDegrees,
            double latitudeDegrees,
            RefractionMode mode,
            double pressureHpa,
            double temperatureCelsius,
            double elevationMeters
    ) {
        HorizontalDegrees horizontal = equatorialToHorizontal(raDegrees, decDegrees, localApparentSiderealDegrees, latitudeDegrees);
        RefractionCorrection refraction = bennettRefractionArcminutes(
                horizontal.altitudeDegrees, mode, pressureHpa, temperatureCelsius, elevationMeters);
        EquatorialDegrees corrected = horizontalToEquatorial(
                horizontal.altitudeDegrees + refraction.arcminutes / 60.0,
                horizontal.azimuthDegrees,
                localApparentSiderealDegrees,
                latitudeDegrees
        );

        return new ObservedEquatorial(
                corrected.raDegrees,
                corrected.decDegrees,
                horizontal.altitudeDegrees,
                horizontal.azimuthDegrees,
                refraction.arcminutes,
                refraction.description
        );
    }

    private static RefractionCorrection bennettRefractionArcminutes(
            double altitudeDegrees,
            RefractionMode inputMode,
            double inputPressureHpa,
            double inputTemperatureCelsius,
            double inputElevationMeters
    ) {
        RefractionMode mode = inputMode == null ? RefractionMode.FIXED_BENNETT : inputMode;
        if (mode == RefractionMode.OFF || altitudeDegrees <= -1.0 || altitudeDegrees >= 89.9) {
            return new RefractionCorrection(0.0, "Refraction off or outside correction altitude range.");
        }

        double bennettBaseArcminutes =
                1.02 / Math.tan((altitudeDegrees + 10.3 / (altitudeDegrees + 5.11)) * AstroMath.DEG_TO_RAD);

        if (mode == RefractionMode.FIXED_BENNETT) {
            return new RefractionCorrection(
                    bennettBaseArcminutes,
                    String.format(java.util.Locale.US,
                            "Fixed Bennett: %.4f′ at altitude %.3f°.",
                            bennettBaseArcminutes, altitudeDegrees)
            );
        }

        double pressureHpa = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE
                ? estimatePressureFromAltitudeHpa(inputElevationMeters)
                : inputPressureHpa;
        double scaledArcminutes = bennettBaseArcminutes
                * (pressureHpa / 1010.0)
                * (283.0 / (273.0 + inputTemperatureCelsius));
        String pressureSource = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE
                ? String.format(java.util.Locale.US, "estimated pressure from %.0f m altitude", inputElevationMeters)
                : "entered pressure";

        return new RefractionCorrection(
                scaledArcminutes,
                String.format(java.util.Locale.US,
                        "Scaled Bennett: %.4f′ at altitude %.3f°, %.1f hPa (%s), %.1f°C.",
                        scaledArcminutes, altitudeDegrees, pressureHpa, pressureSource, inputTemperatureCelsius)
        );
    }

    private static double estimatePressureFromAltitudeHpa(double altitudeMeters) {
        return 1013.25 * Math.pow(1.0 - 2.25577e-5 * altitudeMeters, 5.25588);
    }

    private static HorizontalDegrees equatorialToHorizontal(
            double raDegrees,
            double decDegrees,
            double localApparentSiderealDegrees,
            double latitudeDegrees
    ) {
        double hourAngleRadians = AstroMath.normalizeDegrees(localApparentSiderealDegrees - raDegrees) * AstroMath.DEG_TO_RAD;
        double decRadians = decDegrees * AstroMath.DEG_TO_RAD;
        double latRadians = latitudeDegrees * AstroMath.DEG_TO_RAD;

        double sinAltitude = Math.sin(latRadians) * Math.sin(decRadians)
                + Math.cos(latRadians) * Math.cos(decRadians) * Math.cos(hourAngleRadians);
        double altitudeRadians = Math.asin(AstroMath.clamp(sinAltitude, -1.0, 1.0));
        double cosAltitude = Math.cos(altitudeRadians);

        double sinAzimuth = -Math.sin(hourAngleRadians) * Math.cos(decRadians) / cosAltitude;
        double cosAzimuth = (Math.sin(decRadians) - Math.sin(altitudeRadians) * Math.sin(latRadians))
                / (cosAltitude * Math.cos(latRadians));

        return new HorizontalDegrees(
                altitudeRadians * AstroMath.RAD_TO_DEG,
                AstroMath.normalizeDegrees(Math.atan2(sinAzimuth, cosAzimuth) * AstroMath.RAD_TO_DEG)
        );
    }

    private static EquatorialDegrees horizontalToEquatorial(
            double altitudeDegrees,
            double azimuthDegrees,
            double localApparentSiderealDegrees,
            double latitudeDegrees
    ) {
        double altitudeRadians = altitudeDegrees * AstroMath.DEG_TO_RAD;
        double azimuthRadians = azimuthDegrees * AstroMath.DEG_TO_RAD;
        double latitudeRadians = latitudeDegrees * AstroMath.DEG_TO_RAD;

        double sinDec = Math.sin(altitudeRadians) * Math.sin(latitudeRadians)
                + Math.cos(altitudeRadians) * Math.cos(latitudeRadians) * Math.cos(azimuthRadians);
        double decRadians = Math.asin(AstroMath.clamp(sinDec, -1.0, 1.0));
        double sinHourAngle = -Math.sin(azimuthRadians) * Math.cos(altitudeRadians) / Math.cos(decRadians);
        double cosHourAngle = (Math.sin(altitudeRadians) - Math.sin(latitudeRadians) * Math.sin(decRadians))
                / (Math.cos(latitudeRadians) * Math.cos(decRadians));
        double hourAngleDegrees = AstroMath.normalizeDegrees(Math.atan2(sinHourAngle, cosHourAngle) * AstroMath.RAD_TO_DEG);

        return new EquatorialDegrees(
                AstroMath.normalizeDegrees(localApparentSiderealDegrees - hourAngleDegrees),
                decRadians * AstroMath.RAD_TO_DEG
        );
    }

    private static double interpolateReticleRadiusPx(double decimalYear) {
        if (decimalYear <= POLARIS_RETICLE_YEAR_RADII[0].year) {
            return linearInterpolateRadius(POLARIS_RETICLE_YEAR_RADII[0], POLARIS_RETICLE_YEAR_RADII[1], decimalYear);
        }

        int lastIndex = POLARIS_RETICLE_YEAR_RADII.length - 1;
        if (decimalYear >= POLARIS_RETICLE_YEAR_RADII[lastIndex].year) {
            return linearInterpolateRadius(POLARIS_RETICLE_YEAR_RADII[lastIndex - 1], POLARIS_RETICLE_YEAR_RADII[lastIndex], decimalYear);
        }

        for (int index = 0; index < POLARIS_RETICLE_YEAR_RADII.length - 1; index++) {
            YearRadius lower = POLARIS_RETICLE_YEAR_RADII[index];
            YearRadius upper = POLARIS_RETICLE_YEAR_RADII[index + 1];
            if (decimalYear >= lower.year && decimalYear <= upper.year) {
                return linearInterpolateRadius(lower, upper, decimalYear);
            }
        }

        return POLARIS_RETICLE_YEAR_RADII[lastIndex].radius;
    }

    private static double linearInterpolateRadius(YearRadius lower, YearRadius upper, double decimalYear) {
        double fraction = (decimalYear - lower.year) / (upper.year - lower.year);
        return lower.radius + fraction * (upper.radius - lower.radius);
    }

    private static ReticlePoint polarReticlePoint(double angleDegrees, double radiusPx) {
        double angleRadians = angleDegrees * AstroMath.DEG_TO_RAD;
        return new ReticlePoint(
                RETICLE_CENTER_X + radiusPx * Math.sin(angleRadians),
                RETICLE_CENTER_Y - radiusPx * Math.cos(angleRadians)
        );
    }

    private static int monthDayToOrdinal(int month, int day) {
        int ordinal = 0;
        for (int index = 0; index < month - 1; index++) {
            ordinal += MONTH_DAYS[index];
        }
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

    private static double[] equatorialToUnitVector(double raDegrees, double decDegrees) {
        double raRadians = raDegrees * AstroMath.DEG_TO_RAD;
        double decRadians = decDegrees * AstroMath.DEG_TO_RAD;
        return new double[]{
                Math.cos(decRadians) * Math.cos(raRadians),
                Math.cos(decRadians) * Math.sin(raRadians),
                Math.sin(decRadians)
        };
    }

    private static EquatorialDegrees unitVectorToEquatorial(double[] vector) {
        return new EquatorialDegrees(
                AstroMath.normalizeDegrees(Math.atan2(vector[1], vector[0]) * AstroMath.RAD_TO_DEG),
                Math.asin(AstroMath.clamp(vector[2], -1.0, 1.0)) * AstroMath.RAD_TO_DEG
        );
    }

    private static double[] normalizeVector3(double[] vector) {
        double length = Math.sqrt(dotProduct3(vector, vector));
        return new double[]{vector[0] / length, vector[1] / length, vector[2] / length};
    }

    private static double dotProduct3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double arcsecondsToRadians(double arcseconds) {
        return (arcseconds / 3600.0) * AstroMath.DEG_TO_RAD;
    }

    private static double sinDegrees(double degrees) {
        return Math.sin(degrees * AstroMath.DEG_TO_RAD);
    }

    private static double cosDegrees(double degrees) {
        return Math.cos(degrees * AstroMath.DEG_TO_RAD);
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

    private static final class YearRadius {
        final double year;
        final double radius;
        YearRadius(double year, double radius) {
            this.year = year;
            this.radius = radius;
        }
    }

    private static final class MonthDay {
        final int month;
        final int day;
        MonthDay(int month, int day) {
            this.month = month;
            this.day = day;
        }
    }

    private static final class ReticlePoint {
        final double x;
        final double y;
        ReticlePoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class EquatorialDegrees {
        final double raDegrees;
        final double decDegrees;
        EquatorialDegrees(double raDegrees, double decDegrees) {
            this.raDegrees = raDegrees;
            this.decDegrees = decDegrees;
        }
    }

    private static final class ObservedEquatorial {
        final double raDegrees;
        final double decDegrees;
        final double trueAltitudeDegrees;
        final double trueAzimuthDegrees;
        final double refractionArcMinutes;
        final String refractionDescription;

        ObservedEquatorial(
                double raDegrees,
                double decDegrees,
                double trueAltitudeDegrees,
                double trueAzimuthDegrees,
                double refractionArcMinutes,
                String refractionDescription
        ) {
            this.raDegrees = raDegrees;
            this.decDegrees = decDegrees;
            this.trueAltitudeDegrees = trueAltitudeDegrees;
            this.trueAzimuthDegrees = trueAzimuthDegrees;
            this.refractionArcMinutes = refractionArcMinutes;
            this.refractionDescription = refractionDescription;
        }
    }

    private static final class HorizontalDegrees {
        final double altitudeDegrees;
        final double azimuthDegrees;
        HorizontalDegrees(double altitudeDegrees, double azimuthDegrees) {
            this.altitudeDegrees = altitudeDegrees;
            this.azimuthDegrees = azimuthDegrees;
        }
    }

    private static final class NutationValues {
        final double deltaPsiDegrees;
        final double deltaEpsilonDegrees;
        final double meanObliquityDegrees;
        final double trueObliquityDegrees;
        NutationValues(double deltaPsiDegrees, double deltaEpsilonDegrees, double meanObliquityDegrees, double trueObliquityDegrees) {
            this.deltaPsiDegrees = deltaPsiDegrees;
            this.deltaEpsilonDegrees = deltaEpsilonDegrees;
            this.meanObliquityDegrees = meanObliquityDegrees;
            this.trueObliquityDegrees = trueObliquityDegrees;
        }
    }

    private static final class SunLongitudeAndObliquity {
        final double longitudeDegrees;
        final double meanObliquityDegrees;
        SunLongitudeAndObliquity(double longitudeDegrees, double meanObliquityDegrees) {
            this.longitudeDegrees = longitudeDegrees;
            this.meanObliquityDegrees = meanObliquityDegrees;
        }
    }

    private static final class RefractionCorrection {
        final double arcminutes;
        final String description;
        RefractionCorrection(double arcminutes, String description) {
            this.arcminutes = arcminutes;
            this.description = description;
        }
    }

    private static final class PolarisReticlePosition {
        final double meanRaDegrees;
        final double meanDecDegrees;
        final double observedRaDegrees;
        final double observedDecDegrees;
        final double trueAltitudeDegrees;
        final double trueAzimuthDegrees;
        final double refractionArcMinutes;
        final String refractionDescription;
        final double apparentHourAngleDegrees;
        final double clockAngleDegrees;
        final double observedSeparationArcMinutes;
        final double pixelPerTangentRadian;
        final double radiusReticlePx;
        final double nominalRingRadiusReticlePx;
        final double markerReticleX;
        final double markerReticleY;

        PolarisReticlePosition(
                double meanRaDegrees,
                double meanDecDegrees,
                double observedRaDegrees,
                double observedDecDegrees,
                double trueAltitudeDegrees,
                double trueAzimuthDegrees,
                double refractionArcMinutes,
                String refractionDescription,
                double apparentHourAngleDegrees,
                double clockAngleDegrees,
                double observedSeparationArcMinutes,
                double pixelPerTangentRadian,
                double radiusReticlePx,
                double nominalRingRadiusReticlePx,
                double markerReticleX,
                double markerReticleY
        ) {
            this.meanRaDegrees = meanRaDegrees;
            this.meanDecDegrees = meanDecDegrees;
            this.observedRaDegrees = observedRaDegrees;
            this.observedDecDegrees = observedDecDegrees;
            this.trueAltitudeDegrees = trueAltitudeDegrees;
            this.trueAzimuthDegrees = trueAzimuthDegrees;
            this.refractionArcMinutes = refractionArcMinutes;
            this.refractionDescription = refractionDescription;
            this.apparentHourAngleDegrees = apparentHourAngleDegrees;
            this.clockAngleDegrees = clockAngleDegrees;
            this.observedSeparationArcMinutes = observedSeparationArcMinutes;
            this.pixelPerTangentRadian = pixelPerTangentRadian;
            this.radiusReticlePx = radiusReticlePx;
            this.nominalRingRadiusReticlePx = nominalRingRadiusReticlePx;
            this.markerReticleX = markerReticleX;
            this.markerReticleY = markerReticleY;
        }
    }
}
