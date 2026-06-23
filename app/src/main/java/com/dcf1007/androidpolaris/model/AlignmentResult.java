package com.dcf1007.androidpolaris.model;

/**
 * Calculation result in the original SVG coordinate system.
 *
 * <p>Keeping all reticle positions in SVG-space lets the Android overlay drive the
 * original SVG directly: viewBox 1501.99 x 1498.19, NCP centre at (746.01, 746.43),
 * and Star Adventurer date/time/polar-scope groups updated by transform.</p>
 */
public final class AlignmentResult {
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
    public final double trueAltitudeRadians;
    public final double trueAzimuthRadians;
    public final double refractionArcMinutes;
    public final String refractionDescription;
    public final double markerSvgX;
    public final double markerSvgY;
    public final double radiusSvgPixels;
    public final double nominalRingRadiusSvgPixels;
    public final double pixelPerTangentRadian;
    public final String warningText;

    public AlignmentResult(
            double julianDateUtc,
            double julianDateTt,
            double deltaTSeconds,
            double localMeanSiderealTimeDegrees,
            double localApparentSiderealTimeDegrees,
            double calculatedTargetHourAngleDegrees,
            double calculatedTargetHourAngleHours,
            double activeHourAngleDegrees,
            double activeHourAngleHours,
            double activeHourAngleDisplayAngleDegrees,
            double dateAndPolarReticleRotationDegrees,
            int activeOffsetMonth,
            int activeOffsetDay,
            int zeroHourDateMonth,
            int zeroHourDateDay,
            double polarisHourAngleHours,
            double polarisClockAngleDegrees,
            double apparentRightAscensionRadians,
            double apparentDeclinationRadians,
            double trueAltitudeRadians,
            double trueAzimuthRadians,
            double refractionArcMinutes,
            String refractionDescription,
            double markerSvgX,
            double markerSvgY,
            double radiusSvgPixels,
            double nominalRingRadiusSvgPixels,
            double pixelPerTangentRadian,
            String warningText
    ) {
        this.julianDateUtc = julianDateUtc;
        this.julianDateTt = julianDateTt;
        this.deltaTSeconds = deltaTSeconds;
        this.localMeanSiderealTimeDegrees = localMeanSiderealTimeDegrees;
        this.localApparentSiderealTimeDegrees = localApparentSiderealTimeDegrees;
        this.calculatedTargetHourAngleDegrees = calculatedTargetHourAngleDegrees;
        this.calculatedTargetHourAngleHours = calculatedTargetHourAngleHours;
        this.activeHourAngleDegrees = activeHourAngleDegrees;
        this.activeHourAngleHours = activeHourAngleHours;
        this.activeHourAngleDisplayAngleDegrees = activeHourAngleDisplayAngleDegrees;
        this.dateAndPolarReticleRotationDegrees = dateAndPolarReticleRotationDegrees;
        this.activeOffsetMonth = activeOffsetMonth;
        this.activeOffsetDay = activeOffsetDay;
        this.zeroHourDateMonth = zeroHourDateMonth;
        this.zeroHourDateDay = zeroHourDateDay;
        this.polarisHourAngleHours = polarisHourAngleHours;
        this.polarisClockAngleDegrees = polarisClockAngleDegrees;
        this.apparentRightAscensionRadians = apparentRightAscensionRadians;
        this.apparentDeclinationRadians = apparentDeclinationRadians;
        this.trueAltitudeRadians = trueAltitudeRadians;
        this.trueAzimuthRadians = trueAzimuthRadians;
        this.refractionArcMinutes = refractionArcMinutes;
        this.refractionDescription = refractionDescription;
        this.markerSvgX = markerSvgX;
        this.markerSvgY = markerSvgY;
        this.radiusSvgPixels = radiusSvgPixels;
        this.nominalRingRadiusSvgPixels = nominalRingRadiusSvgPixels;
        this.pixelPerTangentRadian = pixelPerTangentRadian;
        this.warningText = warningText == null ? "" : warningText;
    }
}
