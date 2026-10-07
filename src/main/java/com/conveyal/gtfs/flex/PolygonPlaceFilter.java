package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.gtfs.geom.JTSConverter;
import com.conveyal.gtfs.geom.PointInPolygonTester;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import gnu.trove.set.TIntSet;

import static com.conveyal.r5.common.GeometryUtils.envelopeToFixed;

/// The implementation of [OnDemandPlaceFilter] for polygonal zones. A state or point is within the
/// place when its coordinates fall inside the polygon. The only slow part of containment testing is
/// the preparatory calculations, so one prepared tester is built per filter instance and reused for
/// all tests during a request.
public class PolygonPlaceFilter implements OnDemandPlaceFilter {

    private final CPolygon polygon;

    private final PointInPolygonTester tester;

    private final StreetLayer streetLayer;

    /// Reusable cursor to avoid excessive object creation in vertex containment loops.
    private final VertexStore.Vertex vertex;

    public PolygonPlaceFilter (CPolygon polygon, StreetLayer streetLayer) {
        this.polygon = polygon;
        this.tester = new PointInPolygonTester(polygon);
        this.streetLayer = streetLayer;
        this.vertex = streetLayer.vertexStore.getCursor();
    }

    @Override
    public boolean containsVertex (int vertexIndex) {
        vertex.seek(vertexIndex);
        return tester.contains(vertex.getLon(), vertex.getLat());
    }

    @Override
    public boolean containsPoint (double lat, double lon, Split split) {
        return tester.contains(lon, lat);
    }

    /// The polygon's bounding box is in floating-point WGS84 while the street spatial index holds
    /// fixed-point envelopes. The index contains only the even (forward) edge of each pair.
    @Override
    public TIntSet candidateEdges () {
        return streetLayer.spatialIndex.query(envelopeToFixed(JTSConverter.toJts(polygon.toBox())));
    }

}
