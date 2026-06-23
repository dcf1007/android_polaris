package com.dcf1007.androidpolaris.astro;

import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline Polaris alignment engine.
 *
 * This class is the native Android counterpart to the verified browser model.
 * It computes Polaris as an apparent/topocentric polar-scope target using:
 *
 * 1. Polaris J2000/ICRS catalogue position.
 * 2. Proper motion to the selected epoch.
 * 3. J2000-to-date precession.
 * 4. Compact nutation and annual aberration.
 * 5. Local apparent sidereal time from UTC-as-UT1 plus equation of equinoxes.
 * 6. Hour angle and optional atmospheric refraction.
 * 7. Tangent-plane radial scaling into the same SVG coordinate system used by
 *    the original web reticle.
 *
 * The implementation is intentionally dependency-free so the installed app can
 * calculate offline and the GitHub Action can build without native astronomy
 * libraries.
 */
public final class PolarisAlignmentCalculator {
    public static final double SVG_VIEWBOX_WIDTH = 1501.99;
    public static final double SVG_VIEWBOX_HEIGHT = 1498.19;
    public static final double RETICLE_CENTER_X = 746.01;
    public static final double RETICLE_CENTER_Y = 746.43;

    private static final double POLARIS_RA_J2000_RADIANS =
            AstroMath.hoursMinutesSecondsToDegrees(2.0, 31.0, 49.09456) * AstroMath.DEG_TO_RAD;
    private static final double POLARIS_DEC_J2000_RADIANS =
            AstroMath.degreesMinutesSecondsToDegrees(89.0, 15.0, 50.7923) * AstroMath.DEG_TO_RAD;

    /** Polaris proper motion in RA* = dRA/dt × cos(dec), in milliarcseconds/year. */
    private static final double POLARIS_PM_RA_STAR_MAS_PER_YEAR = 44.48;

    /** Polaris declination proper motion, in milliarcseconds/year. */
    private static final double POLARIS_PM_DEC_MAS_PER_YEAR = -11.85;

    /** Printed reticle year-ring calibration inherited from the verified SVG. */
    private static final double REFERENCE_RING_YEAR = 2020.0;
    private static final double REFERENCE_RING_RADIUS_SVG_PX = 342.5;
    private static final double RING_RADIUS_SVG_PX_PER_YEAR = -2.0;

    public AlignmentResult calculate(AlignmentInput input) {
        validateInput(input);

        double julianDateUtc = AstroMath.julianDateUtc(input.utcInstant);
        double deltaTSeconds = AstroMath.deltaTSeconds(input.utcInstant);
        double julianDateTt = julianDateUtc + deltaTSeconds / 86_400.0;

        // TT is used for apparent-place terms. UTC is retained as the UT1 proxy
        // for Earth rotation and sidereal time because no live IERS data is used.
        double centuriesTt = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double centuriesUtcAsUt1 = (julianDateUtc - AstroMath.J2000_JULIAN_DATE) / 36525.0;
        double decimalYearUtc = AstroMath.decimalYearUtc(input.utcInstant);

        ApparentPolarisPlace apparentPlace = computeApparentPolarisPlace(centuriesTt, julianDateTt);
        double localApparentSiderealTimeDegrees = computeLocalApparentSiderealTimeDegrees(
                julianDateUtc,
                centuriesUtcAsUt1,
                apparentPlace.nutation,
                input.longitudeDegreesEast
        );

        double hourAngleRadians = AstroMath.normalizeRadians(
                localApparentSiderealTimeDegrees * AstroMath.DEG_TO_RAD - apparentPlace.apparentRightAscensionRadians
        );
        double hourAngleHours = AstroMath.radiansToHours(hourAngleRadians);

        double latitudeRadians = input.latitudeDegrees * AstroMath.DEG_TO_RAD;
        HorizontalCoordinates trueHorizontalCoordinates = equatorialToHorizontal(
                hourAngleRadians,
                apparentPlace.apparentDeclinationRadians,
                latitudeRadians
        );

        RefractionCorrection refractionCorrection = computeRefractionCorrection(trueHorizontalCoordinates.altitudeRadians, input);
        double observedDeclinationRadians = apparentPlace.apparentDeclinationRadians + refractionCorrection.radians;

        // Tangent-plane radial scale. The printed year ring is used as the pixel
        // reference, but the observed polar distance is projected with tan(theta)
        // so the reticle coordinates remain geometrically consistent.
        double nominalRingRadiusSvgPx = printedYearRingRadiusSvgPx(decimalYearUtc);
        double meanPolarDistanceRadians = Math.PI / 2.0 - apparentPlace.meanDeclinationRadians;
        double observedPolarDistanceRadians = Math.max(0.0, Math.PI / 2.0 - observedDeclinationRadians);
        double pixelPerTangentRadian = nominalRingRadiusSvgPx / Math.tan(meanPolarDistanceRadians);
        double radiusSvgPx = Math.tan(observedPolarDistanceRadians) * pixelPerTangentRadian;

        // SVG/reticle convention from the browser implementation:
        // clock angle 0° is top, positive screen angle is clockwise, and Polaris
        // placement uses angle = 180° - HA so that the marker follows the printed
        // Star Adventurer reticle orientation.
        double clockAngleDegrees = AstroMath.normalizeDegrees(180.0 - hourAngleHours * 15.0);
        double clockAngleRadians = clockAngleDegrees * AstroMath.DEG_TO_RAD;
        double markerSvgX = RETICLE_CENTER_X + radiusSvgPx * Math.sin(clockAngleRadians);
        double markerSvgY = RETICLE_CENTER_Y - radiusSvgPx * Math.cos(clockAngleRadians);

        List<String> warnings = new ArrayList<>();
        if (input.latitudeDegrees < 0.0) {
            warnings.add("Polaris/NCP alignment is normally not usable from the southern hemisphere.");
        }
        if (trueHorizontalCoordinates.altitudeRadians < 0.0) {
            warnings.add("Polaris is below the mathematical horizon for this site/time.");
        }
        if (decimalYearUtc < 2012.0 || decimalYearUtc > 2032.0) {
            warnings.add("Selected year is outside the printed 2012–2032 ring range; ring scale is extrapolated.");
        }

        return new AlignmentResult(
                julianDateUtc,
                julianDateTt,
                deltaTSeconds,
                localApparentSiderealTimeDegrees,
                hourAngleHours,
                apparentPlace.apparentRightAscensionRadians,
                apparentPlace.apparentDeclinationRadians,
                trueHorizontalCoordinates.altitudeRadians,
                trueHorizontalCoordinates.azimuthRadians,
                refractionCorrection.radians * AstroMath.RAD_TO_DEG * 60.0,
                refractionCorrection.description,
                markerSvgX,
                markerSvgY,
                radiusSvgPx,
                nominalRingRadiusSvgPx,
                pixelPerTangentRadian,
                joinWarnings(warnings)
        );
    }

    private static void validateInput(AlignmentInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Alignment input is required.");
        }
        if (input.utcInstant == null) {
            throw new IllegalArgumentException("Date/time is required.");
        }
        if (!Double.isFinite(input.latitudeDegrees) || input.latitudeDegrees < -90.0 || input.latitudeDegrees > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90° and +90°.");
        }
        if (!Double.isFinite(input.longitudeDegreesEast) || input.longitudeDegreesEast < -180.0 || input.longitudeDegreesEast > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180° and +180°; east is positive.");
        }
    }

    private static ApparentPolarisPlace computeApparentPolarisPlace(double centuriesTt, double julianDateTt) {
        double yearsFromJ2000 = (julianDateTt - AstroMath.J2000_JULIAN_DATE) / 365.25;
        EquatorialCoordinates properMotionPlace = applyProperMotion(yearsFromJ2000);
        EquatorialCoordinates meanPlace = precessJ2000ToDate(
                properMotionPlace.rightAscensionRadians,
                properMotionPlace.declinationRadians,
                centuriesTt
        );
        NutationValues nutation = computeCompactNutation(centuriesTt);
        EquatorialCoordinates nutatedPlace = applyNutation(
                meanPlace.rightAscensionRadians,
                meanPlace.declinationRadians,
                nutation
        );
        EquatorialCoordinates apparentPlace = applyAnnualAberration(
                nutatedPlace.rightAscensionRadians,
                nutatedPlace.declinationRadians,
                centuriesTt,
                nutation.trueObliquityRadians
        );

        return new ApparentPolarisPlace(
                AstroMath.normalizeRadians(apparentPlace.rightAscensionRadians),
                apparentPlace.declinationRadians,
                meanPlace.declinationRadians,
                nutation
        );
    }

    private static EquatorialCoordinates applyProperMotion(double yearsFromJ2000) {
        // Convert catalogue μ_RA* to true RA-angle change by dividing by cos(dec).
        double rightAscensionRadians = AstroMath.normalizeRadians(
                POLARIS_RA_J2000_RADIANS
                        + (POLARIS_PM_RA_STAR_MAS_PER_YEAR / Math.cos(POLARIS_DEC_J2000_RADIANS))
                        * AstroMath.MILLI_ARCSECOND_TO_RAD
                        * yearsFromJ2000
        );
        double declinationRadians = POLARIS_DEC_J2000_RADIANS
                + POLARIS_PM_DEC_MAS_PER_YEAR * AstroMath.MILLI_ARCSECOND_TO_RAD * yearsFromJ2000;
        return new EquatorialCoordinates(rightAscensionRadians, declinationRadians);
    }

    private static EquatorialCoordinates precessJ2000ToDate(double rightAscensionRadians, double declinationRadians, double centuriesTt) {
        // Meeus/IAU 1976 precession. This matches the browser model and is
        // adequate over the printed reticle span; full SOFA/IAU 2006 precession
        // was intentionally not added in this request.
        double zeta = (2306.2181 * centuriesTt
                + 0.30188 * centuriesTt * centuriesTt
                + 0.017998 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;
        double z = (2306.2181 * centuriesTt
                + 1.09468 * centuriesTt * centuriesTt
                + 0.018203 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;
        double theta = (2004.3109 * centuriesTt
                - 0.42665 * centuriesTt * centuriesTt
                - 0.041833 * centuriesTt * centuriesTt * centuriesTt) * AstroMath.ARCSECOND_TO_RAD;

        double a = Math.cos(declinationRadians) * Math.sin(rightAscensionRadians + zeta);
        double b = Math.cos(theta) * Math.cos(declinationRadians) * Math.cos(rightAscensionRadians + zeta)
                - Math.sin(theta) * Math.sin(declinationRadians);
        double c = Math.sin(theta) * Math.cos(declinationRadians) * Math.cos(rightAscensionRadians + zeta)
                + Math.cos(theta) * Math.sin(declinationRadians);

        return new EquatorialCoordinates(
                AstroMath.normalizeRadians(Math.atan2(a, b) + z),
                Math.asin(AstroMath.clamp(c, -1.0, 1.0))
        );
    }

    private static NutationValues computeCompactNutation(double centuriesTt) {
        // Compact IAU 1980/Meeus leading-term nutation used by the web version.
        // The requested TT/UTC separation is included, but full IAU 2000A/2000B
        // nutation was explicitly left out.
        double sunMeanLongitude = AstroMath.normalizeDegrees(280.4665 + 36000.7698 * centuriesTt) * AstroMath.DEG_TO_RAD;
        double moonMeanLongitude = AstroMath.normalizeDegrees(218.3165 + 481267.8813 * centuriesTt) * AstroMath.DEG_TO_RAD;
        double lunarAscendingNode = AstroMath.normalizeDegrees(125.04452 - 1934.136261 * centuriesTt) * AstroMath.DEG_TO_RAD;

        double deltaPsiArcseconds = -17.20 * Math.sin(lunarAscendingNode)
                - 1.32 * Math.sin(2.0 * sunMeanLongitude)
                - 0.23 * Math.sin(2.0 * moonMeanLongitude)
                + 0.21 * Math.sin(2.0 * lunarAscendingNode);
        double deltaEpsilonArcseconds = 9.20 * Math.cos(lunarAscendingNode)
                + 0.57 * Math.cos(2.0 * sunMeanLongitude)
                + 0.10 * Math.cos(2.0 * moonMeanLongitude)
                - 0.09 * Math.cos(2.0 * lunarAscendingNode);

        double meanObliquityArcseconds = 84381.448
                - 46.8150 * centuriesTt
                - 0.00059 * centuriesTt * centuriesTt
                + 0.001813 * centuriesTt * centuriesTt * centuriesTt;
        double meanObliquityRadians = meanObliquityArcseconds * AstroMath.ARCSECOND_TO_RAD;
        double deltaPsiRadians = deltaPsiArcseconds * AstroMath.ARCSECOND_TO_RAD;
        double deltaEpsilonRadians = deltaEpsilonArcseconds * AstroMath.ARCSECOND_TO_RAD;

        return new NutationValues(
                deltaPsiRadians,
                deltaEpsilonRadians,
                meanObliquityRadians,
                meanObliquityRadians + deltaEpsilonRadians
        );
    }

    private static EquatorialCoordinates applyNutation(double rightAscensionRadians, double declinationRadians, NutationValues nutation) {
        double tangentDeclination = Math.tan(declinationRadians);
        double deltaRightAscension = (Math.cos(nutation.meanObliquityRadians)
                + Math.sin(nutation.meanObliquityRadians) * Math.sin(rightAscensionRadians) * tangentDeclination)
                * nutation.deltaPsiRadians
                - Math.cos(rightAscensionRadians) * tangentDeclination * nutation.deltaEpsilonRadians;
        double deltaDeclination = Math.sin(nutation.meanObliquityRadians) * Math.cos(rightAscensionRadians) * nutation.deltaPsiRadians
                + Math.sin(rightAscensionRadians) * nutation.deltaEpsilonRadians;
        return new EquatorialCoordinates(
                AstroMath.normalizeRadians(rightAscensionRadians + deltaRightAscension),
                declinationRadians + deltaDeclination
        );
    }

    private static EquatorialCoordinates applyAnnualAberration(
            double rightAscensionRadians,
            double declinationRadians,
            double centuriesTt,
            double trueObliquityRadians
    ) {
        double sunLongitudeRadians = apparentSunLongitudeRadians(centuriesTt);
        double aberrationConstantRadians = 20.49552 * AstroMath.ARCSECOND_TO_RAD;
        double cosDeclination = Math.cos(declinationRadians);
        double safeCosDeclination = Math.max(1e-12, Math.abs(cosDeclination)) * Math.signum(cosDeclination == 0.0 ? 1.0 : cosDeclination);

        double deltaRightAscension = -aberrationConstantRadians
                * (Math.cos(rightAscensionRadians) * Math.cos(sunLongitudeRadians) * Math.cos(trueObliquityRadians)
                + Math.sin(rightAscensionRadians) * Math.sin(sunLongitudeRadians))
                / safeCosDeclination;
        double deltaDeclination = -aberrationConstantRadians * (
                Math.cos(sunLongitudeRadians) * Math.cos(trueObliquityRadians)
                        * (Math.tan(trueObliquityRadians) * Math.cos(declinationRadians)
                        - Math.sin(rightAscensionRadians) * Math.sin(declinationRadians))
                        + Math.cos(rightAscensionRadians) * Math.sin(declinationRadians) * Math.sin(sunLongitudeRadians)
        );

        return new EquatorialCoordinates(
                AstroMath.normalizeRadians(rightAscensionRadians + deltaRightAscension),
                declinationRadians + deltaDeclination
        );
    }

    private static double apparentSunLongitudeRadians(double centuriesTt) {
        double meanLongitudeDegrees = AstroMath.normalizeDegrees(
                280.46646 + 36000.76983 * centuriesTt + 0.0003032 * centuriesTt * centuriesTt
        );
        double meanAnomalyRadians = AstroMath.normalizeDegrees(
                357.52911 + 35999.05029 * centuriesTt - 0.0001537 * centuriesTt * centuriesTt
                        + centuriesTt * centuriesTt * centuriesTt / 24490000.0
        ) * AstroMath.DEG_TO_RAD;
        double equationOfCenterDegrees = (1.914602 - 0.004817 * centuriesTt - 0.000014 * centuriesTt * centuriesTt) * Math.sin(meanAnomalyRadians)
                + (0.019993 - 0.000101 * centuriesTt) * Math.sin(2.0 * meanAnomalyRadians)
                + 0.000289 * Math.sin(3.0 * meanAnomalyRadians);
        double trueLongitudeDegrees = meanLongitudeDegrees + equationOfCenterDegrees;
        double omegaRadians = (125.04 - 1934.136 * centuriesTt) * AstroMath.DEG_TO_RAD;
        return AstroMath.normalizeDegrees(trueLongitudeDegrees - 0.00569 - 0.00478 * Math.sin(omegaRadians)) * AstroMath.DEG_TO_RAD;
    }

    private static double computeLocalApparentSiderealTimeDegrees(
            double julianDateUtc,
            double centuriesUtcAsUt1,
            NutationValues nutation,
            double longitudeDegreesEast
    ) {
        double daysSinceJ2000 = julianDateUtc - AstroMath.J2000_JULIAN_DATE;
        double greenwichMeanSiderealTimeDegrees = 280.46061837
                + 360.98564736629 * daysSinceJ2000
                + 0.000387933 * centuriesUtcAsUt1 * centuriesUtcAsUt1
                - (centuriesUtcAsUt1 * centuriesUtcAsUt1 * centuriesUtcAsUt1) / 38_710_000.0;

        double equationOfEquinoxesDegrees = nutation.deltaPsiRadians * Math.cos(nutation.trueObliquityRadians) * AstroMath.RAD_TO_DEG;
        return AstroMath.normalizeDegrees(greenwichMeanSiderealTimeDegrees + equationOfEquinoxesDegrees + longitudeDegreesEast);
    }

    private static HorizontalCoordinates equatorialToHorizontal(double hourAngleRadians, double declinationRadians, double latitudeRadians) {
        double east = -Math.cos(declinationRadians) * Math.sin(hourAngleRadians);
        double north = Math.sin(declinationRadians) * Math.cos(latitudeRadians)
                - Math.cos(declinationRadians) * Math.cos(hourAngleRadians) * Math.sin(latitudeRadians);
        double up = Math.sin(declinationRadians) * Math.sin(latitudeRadians)
                + Math.cos(declinationRadians) * Math.cos(hourAngleRadians) * Math.cos(latitudeRadians);
        double[] normalized = AstroMath.normalizeVector(new double[]{east, north, up});

        double altitudeRadians = Math.asin(AstroMath.clamp(normalized[2], -1.0, 1.0));
        double azimuthRadians = AstroMath.normalizeRadians(Math.atan2(normalized[0], normalized[1]));
        return new HorizontalCoordinates(altitudeRadians, azimuthRadians);
    }

    private static RefractionCorrection computeRefractionCorrection(double altitudeRadians, AlignmentInput input) {
        if (input.refractionMode == RefractionMode.OFF) {
            return new RefractionCorrection(0.0, "off");
        }
        if (altitudeRadians < -AstroMath.DEG_TO_RAD) {
            return new RefractionCorrection(0.0, "below Bennett range");
        }

        double pressureHpa = 1010.0;
        double temperatureCelsius = 10.0;
        String description = "fixed Bennett";

        if (input.refractionMode == RefractionMode.SCALED_PRESSURE_TEMPERATURE) {
            pressureHpa = finiteOr(input.pressureHpa, 1010.0);
            temperatureCelsius = finiteOr(input.temperatureCelsius, 10.0);
            description = "scaled P/T";
        } else if (input.refractionMode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE) {
            pressureHpa = pressureFromElevationMeters(finiteOr(input.elevationMeters, 0.0));
            temperatureCelsius = finiteOr(input.temperatureCelsius, 10.0);
            description = "altitude pressure " + String.format(java.util.Locale.US, "%.1f hPa", pressureHpa);
        }

        double altitudeDegrees = altitudeRadians * AstroMath.RAD_TO_DEG;
        double bennettArcMinutes = 1.02 / Math.tan((altitudeDegrees + 10.3 / (altitudeDegrees + 5.11)) * AstroMath.DEG_TO_RAD);
        double scale = (pressureHpa / 1010.0) * (283.0 / (273.0 + temperatureCelsius));
        double refractionRadians = (bennettArcMinutes * scale / 60.0) * AstroMath.DEG_TO_RAD;
        return new RefractionCorrection(refractionRadians, description);
    }

    private static double pressureFromElevationMeters(double elevationMeters) {
        double clampedElevation = AstroMath.clamp(elevationMeters, -500.0, 9000.0);
        return 1013.25 * Math.pow(1.0 - 2.25577e-5 * clampedElevation, 5.25588);
    }

    private static double printedYearRingRadiusSvgPx(double decimalYear) {
        return REFERENCE_RING_RADIUS_SVG_PX + (decimalYear - REFERENCE_RING_YEAR) * RING_RADIUS_SVG_PX_PER_YEAR;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static String joinWarnings(List<String> warnings) {
        if (warnings.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(warnings.get(i));
        }
        return builder.toString();
    }

    private static final class EquatorialCoordinates {
        final double rightAscensionRadians;
        final double declinationRadians;

        EquatorialCoordinates(double rightAscensionRadians, double declinationRadians) {
            this.rightAscensionRadians = rightAscensionRadians;
            this.declinationRadians = declinationRadians;
        }
    }

    private static final class HorizontalCoordinates {
        final double altitudeRadians;
        final double azimuthRadians;

        HorizontalCoordinates(double altitudeRadians, double azimuthRadians) {
            this.altitudeRadians = altitudeRadians;
            this.azimuthRadians = azimuthRadians;
        }
    }

    private static final class NutationValues {
        final double deltaPsiRadians;
        final double deltaEpsilonRadians;
        final double meanObliquityRadians;
        final double trueObliquityRadians;

        NutationValues(double deltaPsiRadians, double deltaEpsilonRadians, double meanObliquityRadians, double trueObliquityRadians) {
            this.deltaPsiRadians = deltaPsiRadians;
            this.deltaEpsilonRadians = deltaEpsilonRadians;
            this.meanObliquityRadians = meanObliquityRadians;
            this.trueObliquityRadians = trueObliquityRadians;
        }
    }

    private static final class ApparentPolarisPlace {
        final double apparentRightAscensionRadians;
        final double apparentDeclinationRadians;
        final double meanDeclinationRadians;
        final NutationValues nutation;

        ApparentPolarisPlace(
                double apparentRightAscensionRadians,
                double apparentDeclinationRadians,
                double meanDeclinationRadians,
                NutationValues nutation
        ) {
            this.apparentRightAscensionRadians = apparentRightAscensionRadians;
            this.apparentDeclinationRadians = apparentDeclinationRadians;
            this.meanDeclinationRadians = meanDeclinationRadians;
            this.nutation = nutation;
        }
    }

    private static final class RefractionCorrection {
        final double radians;
        final String description;

        RefractionCorrection(double radians, String description) {
            this.radians = radians;
            this.description = description;
        }
    }
}
