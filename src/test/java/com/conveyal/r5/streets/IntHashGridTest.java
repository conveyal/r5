package com.conveyal.r5.streets;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;

import static com.conveyal.r5.common.GeometryUtils.geometryFactory;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of spatial index insertion for long line geometries. These are regression tests for two
/// defects that coexisted in IntHashGrid.insert(LineString) from 2016 to 2026. They only affected
/// very long straight roads with no intermediate points, which are rare in urban contexts but may
/// exist in wilderness, rural plains areas, or test scenes.
///
/// Before the fix, a sign error mirrored segments around the start point. Additionally, operations
/// were performed on mixed units of floating and fixed-point degrees, which caused the segment
/// splitting code path to always be skipped, inserting segments over the entire bounding rectangle.
///
/// Most segments between adjacent nodes of an OSM way are short relative to the ~200m index bins,
/// so both defects were rare and went unnoticed. Only very long straight segments were invisible
/// to stop linking and on-demand drop-off clipping.
///
/// Because the contract of index queries allows overselection, tests asserting empty query results
/// deliberately query several bins away from the geometry's actual location, such that the current
/// indexing and overselection cannot see them, but they have been shown to definitely fail on the
/// problematic mirror image insertion that happened before the fix.
public class IntHashGridTest {

    /// Bin sizes are about 0.0018 degrees of latitude (200 meters). All test geometries are
    /// near 45 degrees north, several bins long in each relevant direction.
    private static final double LAT = 45.0;

    /// A straight two-point segment about 1.5 kilometers long must be findable along its whole
    /// length, in particular near its far end, many bins from its start point.
    @Test
    void longStraightSegment () {
        IntHashGrid grid = new IntHashGrid();
        grid.insert(line(0.000, LAT, 0.020, LAT), 7);
        assertTrue(grid.query(fixedEnvelope(0.014, 0.016, LAT - 0.001, LAT + 0.001)).contains(7),
            "The far half of a long straight segment should be present in the index.");
    }

    /// The same segment must not appear at its mirror image about its start point, where the bug
    /// indexed it.
    @Test
    void longStraightSegmentAbsentAtMirrorImage () {
        IntHashGrid grid = new IntHashGrid();
        grid.insert(line(0.000, LAT, 0.020, LAT), 7);
        assertFalse(grid.query(fixedEnvelope(-0.016, -0.014, LAT - 0.001, LAT + 0.001)).contains(7),
            "A segment should not be indexed at its reflection about its start point.");
    }

    /// A long diagonal segment must be present near the line itself but not in the far corner
    /// of its bounding rectangle. Subdivision during insertion should keep the rectangle's
    /// empty corners out of the index.
    @Test
    void longDiagonalSegmentSubdivided () {
        IntHashGrid grid = new IntHashGrid();
        grid.insert(line(0.000, LAT, 0.020, LAT + 0.018), 7);
        assertTrue(grid.query(fixedEnvelope(0.017, 0.019, LAT + 0.0152, LAT + 0.0172)).contains(7),
            "The diagonal should be present in the index near its far end.");
        assertFalse(grid.query(fixedEnvelope(0.018, 0.020, LAT + 0.000, LAT + 0.002)).contains(7),
            "The empty corner of the diagonal's bounding rectangle should not hold the segment.");
    }

    private static LineString line (double lon0, double lat0, double lon1, double lat1) {
        return geometryFactory.createLineString(
            new Coordinate[] {new Coordinate(lon0, lat0), new Coordinate(lon1, lat1)});
    }

    /// Queries are in fixed-point degrees. Inserted LineStrings are converted from floating degrees.
    private static Envelope fixedEnvelope (double minLon, double maxLon, double minLat, double maxLat) {
        return new Envelope(
            VertexStore.floatingDegreesToFixed(minLon), VertexStore.floatingDegreesToFixed(maxLon),
            VertexStore.floatingDegreesToFixed(minLat), VertexStore.floatingDegreesToFixed(maxLat));
    }

}
