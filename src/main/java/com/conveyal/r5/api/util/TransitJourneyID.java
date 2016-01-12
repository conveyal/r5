package com.conveyal.r5.api.util;

/**
 * Tells which pattern and time in pattern to use for this specific transit
 * 
 * Created by mabu on 21.12.2015.
 */
public class TransitJourneyID {
    //Index of segment pattern @notnull
    public int pattern;
    //index of time in chosen pattern @notnull
    public int time;

    public TransitJourneyID(int patternIdx, int timeIdx) {
        pattern = patternIdx;
        time = timeIdx;
    }
}
