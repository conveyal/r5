package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;

public interface TravelTimeCalculator {
    float getTravelTimems(EdgeStore.Edge edge, int durationSeconds, StreetMode streetMode, ProfileRequest req);
}
