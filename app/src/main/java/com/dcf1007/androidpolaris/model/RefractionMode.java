package com.dcf1007.androidpolaris.model;

/**
 * Atmospheric refraction options used by the alignment calculator.
 *
 * The default intentionally matches the last accepted web version: a fixed
 * Bennett approximation. The scaled modes are available for observers who want
 * to enter local pressure, temperature, or altitude-derived pressure.
 */
public enum RefractionMode {
    FIXED_BENNETT("Fixed Bennett"),
    SCALED_PRESSURE_TEMPERATURE("Scaled P/T Bennett"),
    ALTITUDE_PRESSURE_TEMPERATURE("Altitude + temp Bennett"),
    OFF("Off");

    private final String displayName;

    RefractionMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
