package com.conveyal.gtfs.validator.model;

public enum Priority {
    /** 
     * Something that is likely to break routing results,
     * e.g. stop times out of sequence or high-speed travel
     */
    HIGH,
    
    /** 
     * Something that is likely to break display, but still give accurate routing results,
     * e.g. broken shapes or route long name containing route short name.
     */
    MEDIUM,
    
    /**
     * Something that will not affect user experience but should be corrected as time permits,
     * e.g. unused stops.
     */
    LOW,
    
    /**
     * An error for which we do not have a priority
     */
    UNKNOWN
}
