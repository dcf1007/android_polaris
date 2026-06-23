package com.dcf1007.androidpolaris.model;

import java.util.Date;

/**
 * Immutable input bundle for one alignment calculation.
 *
 * <p>All angular values are expressed in the same convention used by the sanitized
 * browser page: latitude is positive north, longitude is positive east, and target
 * right ascension is expressed as decimal sidereal hours.</p>
 */
public final class AlignmentInput {
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

    public AlignmentInput(
            Date utcInstant,
            double latitudeDegrees,
            double longitudeDegreesEast,
            double targetRightAscensionHours,
            int offsetMonth,
            int offsetDay,
            boolean lockReticleToZeroHourAngle,
            RefractionMode refractionMode,
            double pressureHpa,
            double temperatureCelsius,
            double elevationMeters
    ) {
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
