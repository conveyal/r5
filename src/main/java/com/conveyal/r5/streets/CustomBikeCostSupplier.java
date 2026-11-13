package com.conveyal.r5.streets;

public class CustomBikeCostSupplier implements SingleModeTraversalTimes.Supplier {
    private final CustomCostTags tags;

    public CustomBikeCostSupplier(CustomCostTags tags) {
        this.tags = tags;
    }

    @Override
    public double perceivedLengthMultipler () {
        return tags.bikeFactor;
    }

    @Override
    public int turnTimeSeconds (SingleModeTraversalTimes.TurnDirection turnDirection) {
        return 0;
    }
}