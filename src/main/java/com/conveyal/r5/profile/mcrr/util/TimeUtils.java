package com.conveyal.r5.profile.mcrr.util;


import java.util.Calendar;

public class TimeUtils {
    private enum FormatType { COMPACT, LONG, SHORT }
    private static final boolean USE_RAW_TIME = false;

    public static String timeToStrCompact(int time) {
        return timeToStrCompact(time, -1);
    }

    public static String timeToStrCompact(int time, int notSetValue) {
        return timeStr(time, notSetValue, FormatType.COMPACT);
    }

    public static String timeToStrCompact(Calendar time) {
        return timeStr(time, FormatType.COMPACT);
    }

    public static String timeToStrLong(int time) {
        return timeToStrLong(time, -1);
    }

    public static String timeToStrLong(int time, int notSetValue) {
        return timeStr(time, notSetValue, FormatType.LONG);
    }

    public static String timeToStrLong(Calendar time) {
        return timeStr(time, FormatType.LONG);
    }

    public static String timeToStrShort(Calendar time) {
        return timeStr(time, FormatType.SHORT);
    }

    public static Calendar midnightOf(Calendar time) {
        final Calendar midnight = (Calendar) time.clone();
        midnight.set(Calendar.HOUR, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight;
    }


    /* private methods */

    private static String timeStr(int time, int notSetValue, FormatType formatType) {
        if(time == notSetValue) {
            return "";
        }
        if(USE_RAW_TIME) {
            return Integer.toString(time);
        }

        int sec = time % 60;
        time =  time / 60;
        int min = time % 60;
        int hour = time / 60;

        return timeStr(hour, min, sec, formatType);
    }

    private static String timeStr(Calendar time, FormatType formatType) {
        if(time == null) {
            return "";
        }
        int sec = time.get(Calendar.SECOND);
        int min = time.get(Calendar.MINUTE);
        int hour = time.get(Calendar.HOUR_OF_DAY);

        return timeStr(hour, min, sec, formatType);
    }

    private static String timeStr( int hour, int min, int sec, FormatType formatType) {
        switch (formatType) {
            case LONG: return timeStrLong(hour, min, sec);
            case SHORT: return timeStrShort(hour, min);
            default: return timeStrCompact(hour, min, sec);
        }
    }

    private static String timeStrCompact(int hour, int min, int sec) {
        return hour == 0 ? String.format("%d:%02d", min, sec) : String.format("%d:%02d:%02d", hour, min, sec);
    }

    private static String timeStrLong(int hour, int min, int sec) {
        return String.format("%02d:%02d:%02d", hour, min, sec);
    }

    private static String timeStrShort(int hour, int min) {
        return String.format("%02d:%02d", hour, min);
    }
}
