package com.conveyal.r5.analyst.progress;

import java.util.UUID;

/** API model for tasks in an activity response. Times are durations rather than absolute to counter clock drift. */
public class ApiTask {
    public UUID id;
    public String title;
    public String detail;
    public Task.State state;
    public int percentComplete;
    public int secondsActive;
    public int secondsComplete;
    public WorkProduct workProduct;
}
