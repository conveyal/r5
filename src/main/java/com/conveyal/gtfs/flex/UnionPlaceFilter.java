package com.conveyal.gtfs.flex;

import com.conveyal.r5.streets.Split;
import gnu.trove.set.TIntSet;

/// An [OnDemandPlaceFilter] that accepts anything accepted by either of its wrapped filters.
public class UnionPlaceFilter implements OnDemandPlaceFilter {

    private final OnDemandPlaceFilter a;

    private final OnDemandPlaceFilter b;

    public UnionPlaceFilter (OnDemandPlaceFilter a, OnDemandPlaceFilter b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean containsVertex (int vertexIndex) {
        return a.containsVertex(vertexIndex) || b.containsVertex(vertexIndex);
    }

    @Override
    public boolean containsPoint (double lat, double lon, Split split) {
        return a.containsPoint(lat, lon, split) || b.containsPoint(lat, lon, split);
    }

    /// Candidate edges cannot be combined unless both members can pre-select, because a null
    /// from either member means some passing states would be missed by candidates alone.
    @Override
    public TIntSet clipCandidateEdges () {
        TIntSet ca = a.clipCandidateEdges();
        if (ca == null) return null;
        TIntSet cb = b.clipCandidateEdges();
        if (cb == null) return null;
        ca.addAll(cb);
        return ca;
    }

}
