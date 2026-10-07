package com.conveyal.r5.analyst.network;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/// A PointSetCache for use in tests, serving PointSet instances from an in-memory map rather than deserializing them
/// from files. This lets tests reach the production code path that loads destination PointSets onto a task and wraps
/// grids of unequal extents. Assigning PointSets directly to task fields would bypass that code path, leaving it untested.
public class TestPointSetCache extends PointSetCache {

    private final Map<String, PointSet> pointSets = new HashMap<>();

    public TestPointSetCache () {
        // The superclass file storage is never used because we override the only method that reads from it.
        super(null);
    }

    public void put (String key, PointSet pointSet) {
        checkNotNull(pointSet);
        pointSets.put(key, pointSet);
    }

    @Override
    public PointSet get (String key) {
        PointSet result = pointSets.get(key);
        checkState(result != null, "No PointSet was registered under key: " + key);
        return result;
    }

}
