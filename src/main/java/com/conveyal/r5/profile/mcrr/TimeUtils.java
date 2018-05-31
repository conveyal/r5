package com.conveyal.r5.profile.mcrr;


class TimeUtils {
    private static final boolean USE_RAW_TIME = false;

    static String timeToString(int time, int notSetValue) {
        //return time == notSetValue ? "" : Integer.toString(time);
        return time == notSetValue ? "" : (USE_RAW_TIME ? Integer.toString(time) : timeStr(time));
    }

    private static String timeStr(int time) {
        time /=60;
        int min = time%60;
        int hour = time/60;
        return String.format("%02d:%02d", hour, min);
    }
}
