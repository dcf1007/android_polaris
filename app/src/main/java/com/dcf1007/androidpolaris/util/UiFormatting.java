package com.dcf1007.androidpolaris.util;

import com.dcf1007.androidpolaris.astro.AstroMath;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** UI-only formatting helpers. Calculations stay in model/calculator classes. */
public final class UiFormatting {
    private static final SimpleDateFormat LOCAL_DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private UiFormatting() {
        // Utility class.
    }

    public static String formatLocalDateTime(Date date) {
        synchronized (LOCAL_DATE_TIME_FORMAT) {
            return LOCAL_DATE_TIME_FORMAT.format(date);
        }
    }

    public static Date parseLocalDateTime(String text) throws java.text.ParseException {
        synchronized (LOCAL_DATE_TIME_FORMAT) {
            LOCAL_DATE_TIME_FORMAT.setLenient(false);
            return LOCAL_DATE_TIME_FORMAT.parse(text);
        }
    }

    public static String formatHours(double hours) {
        long totalSeconds = Math.round(normalizeHours(hours) * 3600.0) % 86_400L;
        long hh = totalSeconds / 3600L;
        long mm = (totalSeconds % 3600L) / 60L;
        long ss = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss);
    }

    public static String formatRightAscension(double radians) {
        return formatHours(AstroMath.radiansToHours(radians));
    }

    public static String formatDeclination(double radians) {
        double degrees = radians * AstroMath.RAD_TO_DEG;
        String sign = degrees < 0.0 ? "−" : "+";
        double absoluteDegrees = Math.abs(degrees);
        int wholeDegrees = (int) Math.floor(absoluteDegrees);
        double minutesFloat = (absoluteDegrees - wholeDegrees) * 60.0;
        int minutes = (int) Math.floor(minutesFloat);
        double seconds = (minutesFloat - minutes) * 60.0;
        return String.format(Locale.US, "%s%02d°%02d′%04.1f″", sign, wholeDegrees, minutes, seconds);
    }

    public static String formatDegrees(double radians, int places) {
        return String.format(Locale.US, "%+." + places + "f°", radians * AstroMath.RAD_TO_DEG);
    }

    private static double normalizeHours(double hours) {
        double normalized = hours % 24.0;
        return normalized < 0.0 ? normalized + 24.0 : normalized;
    }
}
