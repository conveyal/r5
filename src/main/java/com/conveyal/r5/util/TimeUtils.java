package com.conveyal.r5.util;


import java.util.Calendar;

public class TimeUtils {
    private static final boolean USE_RAW_TIME = false;

    public static String timeToString(int time, int notSetValue) {
        //return time == notSetValue ? "" : Integer.toString(time);
        return time == notSetValue ? "" : (USE_RAW_TIME ? Integer.toString(time) : timeStr(time));
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

    private static String timeStr(int time) {
        time /=60;
        int min = time%60;
        int hour = time/60;
        return String.format("%02d:%02d", hour, min);
    }
}
