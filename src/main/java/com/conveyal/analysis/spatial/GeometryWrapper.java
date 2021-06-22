package com.conveyal.analysis.spatial;

public abstract class GeometryWrapper {
    public int featureCount;

    GeometryWrapper (int featureCount) {
        this.featureCount = featureCount;
    }

}
