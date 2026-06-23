package com.dcf1007.androidpolaris.model;

import java.util.Date;

/**
 * Immutable input bundle for one Polaris alignment calculation.
 *
 * Longitude is positive east. Latitude is positive north. Pressure and
 * temperature are used only when the selected refraction mode needs them.
 */
public final class AlignmentInput {
    public final Date utcInstant;
    public final double latitudeDegrees;
    public final double longitudeDegreesEast;
    public final RefractionMode refractionMode;
    public final double pressureHpa;
    public final double temperatureCelsius;
    public final double elevationMeters;

    public AlignmentInput(
            Date utcInstant,
            double latitudeDegrees,
            double longitudeDegreesEast,
            RefractionMode refractionMode,
            double pressureHpa,
            double temperatureCelsius,
            double elevationMeters
    ) {
        this.utcInstant = new Date(utcInstant.getTime());
        this.latitudeDegrees = latitudeDegrees;
        this.longitudeDegreesEast = longitudeDegreesEast;
        this.refractionMode = refractionMode;
        this.pressureHpa = pressureHpa;
        this.temperatureCelsius = temperatureCelsius;
        this.elevationMeters = elevationMeters;
    }
}
