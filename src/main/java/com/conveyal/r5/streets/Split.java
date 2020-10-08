package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.TIntCollection;
import org.apache.commons.math3.util.FastMath;
import org.geotools.referencing.GeodeticCalculator;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a potential split point along an existing edge, retaining some geometric calculation state so that
 * once the best candidate is found more detailed calculations can continue.
 */
public class Split {

    private static final Logger LOG = LoggerFactory.getLogger(Split.class);

    public int edge = -1; // TODO clarify is this the even edge number of a pair?
    public int seg = 0; // the segment within the edge that is closest to the search point
    public double frac = 0; // the fraction along that segment where a link should occur
    public int fixedLon; // the x coordinate of the split point along the edge
    public int fixedLat; // the y coordinate of the split point along the edge
    // We must use a long because squaring a typical search radius in fixed-point _does_ cause signed int32 overflow.
    public long distanceToEdge_squaredFixedDegrees = Long.MAX_VALUE; // squared distance from given point to the split, in degrees

    // The following fields require more calculations and are only set once a best edge is found.

    /**
     * Distance between a requested nearby point and the edge
     */
    public int distanceToEdge_mm = 0;

    /**
     * Accumulated distance from the beginning vertex of the edge geometry up to the split point (point on the edge
     * closest to the point to be linked)
     */
    public int distance0_mm = 0;

    /**
     * Accumulated distance from the end vertex of the edge geometry up to the split point (point on the edge
     * closest to the point to be linked)
     */
    public int distance1_mm = 0;

    public int vertex0; // the vertex at the beginning of the chosen edge
    public int vertex1; // the vertex at the end of the chosen edge

    /**
     * Copy all the fields in another Split into this one.
     * This avoids creating large amounts of tiny short-lived objects.
     * Does not copy distanceToEdge_mm, because this is only set at the very end of the operation, on the winning Split.
     */
    public void setFrom (Split other) {
        edge = other.edge;
        seg = other.seg;
        frac = other.frac;
        fixedLon = other.fixedLon;
        fixedLat = other.fixedLat;
        distanceToEdge_squaredFixedDegrees = other.distanceToEdge_squaredFixedDegrees;
    }

    private static GeodeticCalculator distanceCalculator = new GeodeticCalculator(DefaultGeographicCRS.WGS84);

    /**
     * Find a location on an existing street near the given point, without actually creating any vertices or edges.
     * @return a new Split object, or null if no edge was found in range.
     */
    public static Split find (double lat, double lon, double searchRadiusMeters, StreetLayer streetLayer,
                              StreetMode streetMode) {

        // After this conversion, the entire geometric calculation is happening in fixed precision int degrees.
        int fixedLat = VertexStore.floatingDegreesToFixed(lat);
        int fixedLon = VertexStore.floatingDegreesToFixed(lon);

        // We won't worry about the perpendicular walks yet.
        // Just insert or find a vertex on the nearest road and return that vertex.

        final double metersPerDegreeLat = 111111.111;
        double cosLat = FastMath.cos(FastMath.toRadians(lat)); // The projection factor, Earth is a "sphere"

        // Use longs for radii and their square because squaring the fixed-point radius _will_ overflow a signed int32.
        long radiusFixedLat = VertexStore.floatingDegreesToFixed(searchRadiusMeters / metersPerDegreeLat);
        long radiusFixedLon = (int)(radiusFixedLat / cosLat); // Expand the X search space, don't shrink it.
        Envelope envelope = new Envelope(fixedLon, fixedLon, fixedLat, fixedLat);
        envelope.expandBy(radiusFixedLon, radiusFixedLat);
        long squaredRadiusFixedLat = radiusFixedLat * radiusFixedLat;
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        // Iterate over the set of forward (even) edges that may be near the given coordinate.
        TIntCollection candidateEdges = streetLayer.findEdgesInEnvelope(envelope);
        // The split location currently being examined and the best one seen so far.
        Split curr = new Split();
        Split best = new Split();
        candidateEdges.forEach(e -> {
            curr.edge = e;
            edge.seek(e);

            // Do not consider linking to edges that are links to streets from transit stops, P+Rs, and bike shares.
            // These edges allow all modes to traverse, but may be connected to roads with more restrictive permissions.
            // On a given edge pair both directions will have the same flag.
            if (edge.getFlag(EdgeStore.EdgeFlag.LINK)) return true;

            // If either direction of the current edge doesn't allow the specified mode of travel, skip it.
            // It is arguably better to skip it only if BOTH directions forbid the specified mode (see commented block
            // below). This system has odd effects in areas with lots of one-way streets or divided roads.
            // TODO Really, we want to allow linking to two different edge-pairs in such cases but that is more complex.
            // Do not consider linking to edges that are not marked "linkable". This excludes e.g. tunnels and motorways.
            if (!edge.allowsStreetMode(streetMode) || !edge.getFlag(EdgeStore.EdgeFlag.LINKABLE)) {
                return true;
            }
            edge.advance();
            if (!edge.allowsStreetMode(streetMode) || !edge.getFlag(EdgeStore.EdgeFlag.LINKABLE)) {
                return true;
            }
            edge.retreat();

            // The distance to this edge is the distance to the closest segment of its geometry.
            edge.forEachSegment((seg, fixedLat0, fixedLon0, fixedLat1, fixedLon1) -> {
                // Find the fraction along the current segment
                curr.seg = seg;
                curr.frac = GeometryUtils.segmentFraction(fixedLon0, fixedLat0, fixedLon1, fixedLat1, fixedLon, fixedLat, cosLat);
                // Project to get the closest point on the segment.
                // Note: the fraction is scaleless, xScale is accounted for in the segmentFraction function.
                curr.fixedLon = (int)(fixedLon0 + curr.frac * (fixedLon1 - fixedLon0));
                curr.fixedLat = (int)(fixedLat0 + curr.frac * (fixedLat1 - fixedLat0));
                // Find squared distance to edge (avoid taking square root, which is slow)
                long dx = (long)((curr.fixedLon - fixedLon) * cosLat);
                long dy = (long) (curr.fixedLat - fixedLat);
                curr.distanceToEdge_squaredFixedDegrees = dx * dx + dy * dy;
                // Ignore segments that are too far away (filter false positives).
                if (curr.distanceToEdge_squaredFixedDegrees < squaredRadiusFixedLat) {
                    if (curr.distanceToEdge_squaredFixedDegrees < best.distanceToEdge_squaredFixedDegrees) {
                        // Update the best segment if we've found something closer.
                        best.setFrom(curr);
                    } else if (curr.distanceToEdge_squaredFixedDegrees == best.distanceToEdge_squaredFixedDegrees
                            && curr.edge < best.edge) {
                        // Break distance ties by favoring lower edge IDs. This makes destination linking
                        // deterministic where centroids are equidistant to edges (see issue #159).
                        best.setFrom(curr);
                    }
                }
            });
            // The loop over the edges should continue.
            return true;
        });

        if (best.edge < 0) {
            // No edge found nearby.
            return null;
        }

        // We found an edge. Iterate over its segments again, accumulating distances along its geometry.
        // The distance calculations involve square roots so are deferred to happen here, only on the selected edge.
        // The length is are stored in one-element array to dodge Java's "effectively final" BS.
        edge.seek(best.edge);
        best.vertex0 = edge.getFromVertex();
        best.vertex1 = edge.getToVertex();
        double[] lengthBefore_fixedDeg = new double[1];
        edge.forEachSegment((seg, fLat0, fLon0, fLat1, fLon1) -> {
            // Sum lengths only up to the split point.
            // lengthAfter should be total length minus lengthBefore, which ensures splits do not change total lengths.
            if (seg <= best.seg) {
                double dx = (fLon1 - fLon0) * cosLat;
                double dy = (fLat1 - fLat0);
                double length = FastMath.sqrt(dx * dx + dy * dy);
                if (seg == best.seg) {
                    length *= best.frac;
                }
                lengthBefore_fixedDeg[0] += length;
            }
        });
        // Convert the fixed-precision degree measurements into (milli)meters
        double lengthBefore_floatDeg = VertexStore.fixedDegreesToFloating((int)lengthBefore_fixedDeg[0]);
        best.distance0_mm = (int)(lengthBefore_floatDeg * metersPerDegreeLat * 1000);
        // FIXME perhaps we should be using the sphericalDistanceLibrary here, or the other way around.
        // The initial edge lengths are set using that library on OSM node coordinates, and they are slightly different.
        // We are using a single cosLat value at the linking point, instead of a different value at each segment.
        if (best.distance0_mm < 0) {
            best.distance0_mm = 0;
            LOG.error("Length of first street segment was not positive.");
        }

        if (best.distance0_mm > edge.getLengthMm()) {
            // This mistake happens because the linear distance calculation we're using comes out longer than the
            // spherical distance. The graph remains coherent because we force the two split edge lengths to add up
            // to the original edge length.
            LOG.debug("Length of first street segment was greater than the whole edge ({} > {}).",
                    best.distance0_mm, edge.getLengthMm());
            best.distance0_mm = edge.getLengthMm();
        }
        best.distance1_mm = edge.getLengthMm() - best.distance0_mm;

        // To speed up computation above, square roots were avoided and distanceToEdge_squaredFixedDegrees was
        // calculated using fixed point degrees. We now want to calculate the distance in millimeters, for routing.
        // To do so, we take the square root of distanceToEdge_squaredFixedDegrees, convert to floating point degrees
        // latitude then multiply by the metersPerDegreeLat factor above and 1000 to convert to millimeters.
        // This is accurate enough for our purposes.
        double distanceToEdge_fixedDegrees = FastMath.sqrt(best.distanceToEdge_squaredFixedDegrees);
        double distanceToEdge_floatingDegrees = VertexStore.fixedDegreesToFloating(distanceToEdge_fixedDegrees);
        best.distanceToEdge_mm = (int) (distanceToEdge_floatingDegrees * metersPerDegreeLat * 1000);
        return best;
    }

    /**
     * Find a split on a particular edge.
     * FIXME this contains way too much duplicate code. We can reuse the code in Split.find().
     * we just need a way to supply an edge or edges, instead of using the spatial index.
     */
    public static Split findOnEdge (double lat, double lon, EdgeStore.Edge edge) {

        // After this conversion, the entire geometric calculation is happening in fixed precision int degrees.
        int fixedLat = VertexStore.floatingDegreesToFixed(lat);
        int fixedLon = VertexStore.floatingDegreesToFixed(lon);

        // We won't worry about the perpendicular walks yet.
        // Just insert or find a vertex on the nearest road and return that vertex.

        final double metersPerDegreeLat = 111111.111;
        double cosLat = FastMath.cos(FastMath.toRadians(lat)); // The projection factor, Earth is a "sphere"

        // FIXME this looks like copy-pasted code
        // The split location currently being examined and the best one seen so far.
        Split curr = new Split();
        Split best = new Split();
        curr.edge = edge.edgeIndex;

        best.vertex0 = edge.getFromVertex();
        best.vertex1 = edge.getToVertex();
        double[] lengthBefore_fixedDeg = new double[1];
        edge.forEachSegment((seg, fixedLat0, fixedLon0, fixedLat1, fixedLon1) -> {
            // Find the fraction along the current segment
            curr.seg = seg;
            curr.frac = GeometryUtils.segmentFraction(fixedLon0, fixedLat0, fixedLon1, fixedLat1, fixedLon, fixedLat, cosLat);
            // Project to get the closest point on the segment.
            // Note: the fraction is scaleless, xScale is accounted for in the segmentFraction function.
            curr.fixedLon = (int)(fixedLon0 + curr.frac * (fixedLon1 - fixedLon0));
            curr.fixedLat = (int)(fixedLat0 + curr.frac * (fixedLat1 - fixedLat0));

            double dx = (fixedLon1 - fixedLon0) * cosLat;
            double dy = (fixedLat1 - fixedLat0);
            double length = FastMath.sqrt(dx * dx + dy * dy);

            curr.distance0_mm = (int) ((lengthBefore_fixedDeg[0] + length * curr.frac) * metersPerDegreeLat * 1000);

            lengthBefore_fixedDeg[0] += length;

            curr.distanceToEdge_squaredFixedDegrees = (long)(dx * dx + dy * dy);
            // Replace the best segment if we've found something closer.
            if (curr.distanceToEdge_squaredFixedDegrees < best.distanceToEdge_squaredFixedDegrees) {
                best.setFrom(curr);
            }
        }); // end loop over segments

        int edgeLengthMm = edge.getLengthMm();
        if (best.distance0_mm > edgeLengthMm) {
            // rounding errors
            best.distance0_mm = edgeLengthMm;
            best.distance1_mm = 0;
        }
        else {
            best.distance1_mm = edgeLengthMm - best.distance0_mm;
        }

        return best;
    }
}
