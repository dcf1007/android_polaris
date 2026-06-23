package com.dcf1007.androidpolaris.model;

/**
 * Calculation result in the original SVG coordinate system.
 *
 * Keeping SVG-space coordinates makes the native overlay reproduce the same
 * geometry as the verified browser version: viewBox 1501.99 x 1498.19,
 * NCP centre at (746.01, 746.43), and Polaris year rings in SVG pixels.
 */
public final class AlignmentResult {
    public final double julianDateUtc;
    public final double julianDateTt;
    public final double deltaTSeconds;
    public final double localApparentSiderealTimeDegrees;
    public final double polarisHourAngleHours;
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
            double localApparentSiderealTimeDegrees,
            double polarisHourAngleHours,
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
        this.localApparentSiderealTimeDegrees = localApparentSiderealTimeDegrees;
        this.polarisHourAngleHours = polarisHourAngleHours;
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
