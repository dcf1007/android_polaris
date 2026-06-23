package com.dcf1007.androidpolaris.astro;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Small astronomy/math utility methods shared by the alignment calculator.
 *
 * The methods are deliberately explicit rather than clever. This keeps unit
 * conversions visible and reduces the risk of mixing degrees, radians, hours,
 * arcseconds, and milliseconds in the polar alignment model.
 */
public final class AstroMath {
    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final double RAD_TO_DEG = 180.0 / Math.PI;
    public static final double ARCSECOND_TO_RAD = DEG_TO_RAD / 3600.0;
    public static final double MILLI_ARCSECOND_TO_RAD = ARCSECOND_TO_RAD / 1000.0;
    public static final double MILLIS_PER_DAY = 86_400_000.0;
    public static final double J2000_JULIAN_DATE = 2_451_545.0;

    private AstroMath() {
        // Utility class.
    }

    public static double hoursMinutesSecondsToDegrees(double hours, double minutes, double seconds) {
        return 15.0 * (hours + minutes / 60.0 + seconds / 3600.0);
    }

    public static double degreesMinutesSecondsToDegrees(double degrees, double minutes, double seconds) {
        double sign = degrees < 0.0 ? -1.0 : 1.0;
        return sign * (Math.abs(degrees) + minutes / 60.0 + seconds / 3600.0);
    }

    public static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    public static double normalizeRadians(double radians) {
        double fullTurn = 2.0 * Math.PI;
        double normalized = radians % fullTurn;
        return normalized < 0.0 ? normalized + fullTurn : normalized;
    }

    public static double radiansToHours(double radians) {
        return normalizeRadians(radians) * 12.0 / Math.PI;
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    public static double julianDateUtc(Date date) {
        return date.getTime() / MILLIS_PER_DAY + 2_440_587.5;
    }

    /**
     * Built-in ΔT approximation, in seconds.
     *
     * ΔT = TT - UT. For the current offline app we use UTC as the practical UT1
     * proxy and apply this ΔT only to the slowly varying apparent-place terms.
     * The remaining DUT1 correction would require fresh IERS data and is not
     * embedded, so the app stays fully offline after installation.
     */
    public static double deltaTSeconds(Date date) {
        double decimalYear = decimalYearUtc(date);
        double t = decimalYear - 2000.0;
        if (decimalYear >= 2005.0 && decimalYear <= 2050.0) {
            return 62.92 + 0.32217 * t + 0.005589 * t * t;
        }

        double centuriesFrom2000 = (decimalYear - 2000.0) / 100.0;
        return 64.7 + 64.5 * centuriesFrom2000 + 30.0 * centuriesFrom2000 * centuriesFrom2000;
    }

    public static double decimalYearUtc(Date date) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);

        Calendar start = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        start.clear();
        start.set(Calendar.YEAR, year);
        start.set(Calendar.MONTH, Calendar.JANUARY);
        start.set(Calendar.DAY_OF_MONTH, 1);

        Calendar next = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        next.clear();
        next.set(Calendar.YEAR, year + 1);
        next.set(Calendar.MONTH, Calendar.JANUARY);
        next.set(Calendar.DAY_OF_MONTH, 1);

        double elapsed = date.getTime() - start.getTimeInMillis();
        double yearLength = next.getTimeInMillis() - start.getTimeInMillis();
        return year + elapsed / yearLength;
    }

    public static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] normalizeVector(double[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        return new double[]{vector[0] / length, vector[1] / length, vector[2] / length};
    }
}
