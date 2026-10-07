package com.conveyal.gtfs.flex;

import com.conveyal.r5.streets.Split;
import gnu.trove.set.TIntSet;

/// The implementation of [OnDemandPlaceFilter] for GTFS location_groups which are handled using
/// [MeetingAreas]. A state is within this place when its vertex is in the union of the MeetingAreas
/// of the member stops of the location_group.
///
/// The discovery searches which identify vertices for a meeting area never retain their own walk
/// times. Only membership is tested here, and any state that is retained keeps its own travel time
/// from its own search.
public class MeetingAreaPlaceFilter implements OnDemandPlaceFilter {

    private final TIntSet vertices;

    public MeetingAreaPlaceFilter (TIntSet vertices) {
        this.vertices = vertices;
    }

    @Override
    public boolean containsVertex (int vertexIndex) {
        return vertices.contains(vertexIndex);
    }

    /// A vertex set is not a geometry and has no "interior" that could contain off-network points,
    /// so a point is within the area when the edge it splits ends at a vertex in the area. This
    /// reflects how origin points are considered to be located relative to the network.
    @Override
    public boolean containsPoint (double lat, double lon, Split split) {
        return vertices.contains(split.vertex0) || vertices.contains(split.vertex1);
    }

    /// Meeting areas are small sets of vertex IDs. Scanning the full set should be inexpensive.
    @Override
    public TIntSet candidateEdges () {
        return null;
    }

}
