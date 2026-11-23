package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.model.Trip;

/// This subclass includes the extra fields that are used only for on-demand GTFS-flex trips.
/// This avoids having even more uninitialized fields in every instance of Trip, and also allows
/// distinguishing which trips have been judged to be flex-specific.
/// See https://github.com/google/transit/pull/598 for field definitions.
public class FlexTrip extends Trip {
    public double safe_duration_factor = 1.0;
    public double safe_duration_offset = 0.0;
}
