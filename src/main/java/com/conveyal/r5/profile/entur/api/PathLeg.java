package com.conveyal.r5.profile.entur.api;

public interface PathLeg {
    int fromStop();
    int fromTime();

    int toStop();
    int toTime();

    int pattern();
    int trip();

    boolean isTransfer();
    boolean isTransit();
}
