package com.conveyal.r5.streets;

public class CustomWalkCostSupplier implements SingleModeTraversalTimes.Supplier {
    private final CustomCostTags tags;

    public CustomWalkCostSupplier(CustomCostTags tags) {
        this.tags = tags;
    }

    @Override
    public double perceivedLengthMultipler () {
        return tags.walkFactor;
    }

    @Override
    public int turnTimeSeconds (SingleModeTraversalTimes.TurnDirection turnDirection) {
        return 0;
    }
}