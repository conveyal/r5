package com.conveyal.analysis.spatial;

public abstract class GeometryWrapper {
    int featureCount;

    public GeometryWrapper (int featureCount) {
        this.featureCount = featureCount;
    }

}
