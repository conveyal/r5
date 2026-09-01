package com.conveyal.gtfs.geom;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of compact polygonal geometries (simple and multi). Tests include conversion to and
/// from JTS, bounding boxes and containment, and rejection of degenerate geometry.
class CMultiPolygonTest {

    private static final String TWO_SQUARES =
        "MULTIPOLYGON (((0 0, 1 0, 1 1, 0 1, 0 0)), ((3 0, 4 0, 4 1, 3 1, 3 0)))";

    private static final String SQUARE_WITH_HOLE =
        "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 3 1, 3 3, 1 3, 1 1))";

    private static Geometry wkt (String wkt) throws Exception {
        return new WKTReader(JTSConverter.GEOMETRY_FACTORY).read(wkt);
    }

    @Test
    void multiPolygonRoundTrip () throws Exception {
        Geometry jts = wkt(TWO_SQUARES);
        CPolygonal compact = CPolygonal.fromJts((MultiPolygon) jts);
        assertInstanceOf(CMultiPolygon.class, compact);
        assertEquals(2, ((CMultiPolygon) compact).getPolygons().length);
        assertTrue(jts.equalsExact(compact.toJts()), "Converting to compact form and back should preserve the geometry.");
    }

    @Test
    void polygonWithHoleRoundTrip () throws Exception {
        Geometry jts = wkt(SQUARE_WITH_HOLE);
        CPolygonal compact = CPolygonal.fromJts((org.locationtech.jts.geom.Polygonal) jts);
        assertInstanceOf(CPolygonWithHoles.class, compact);
        assertTrue(jts.equalsExact(compact.toJts()));
        assertTrue(compact.validate());
    }

    @Test
    void boxSpansAllPolygons () throws Exception {
        CPolygonal compact = CPolygonal.fromJts((MultiPolygon) wkt(TWO_SQUARES));
        CBox box = compact.toBox();
        assertEquals(0, box.minLon);
        assertEquals(4, box.maxLon);
        assertEquals(0, box.minLat);
        assertEquals(1, box.maxLat);
    }

    /// Points located inside either component polygon are contained.
    /// Points located in the gap between the polygons are not.
    @Test
    void containmentOverParts () throws Exception {
        CPolygonal compact = CPolygonal.fromJts((MultiPolygon) wkt(TWO_SQUARES));
        PointInPolygonTester tester = new PointInPolygonTester(compact);
        assertTrue(tester.contains(0.5, 0.5));
        assertTrue(tester.contains(3.5, 0.5));
        assertFalse(tester.contains(2, 0.5));
        assertFalse(tester.contains(0.5, 1.5));
    }

    @Test
    void holeIsOutside () throws Exception {
        CPolygonal compact = CPolygonal.fromJts((org.locationtech.jts.geom.Polygonal) wkt(SQUARE_WITH_HOLE));
        PointInPolygonTester tester = new PointInPolygonTester(compact);
        assertTrue(tester.contains(0.5, 0.5));
        assertFalse(tester.contains(2, 2));
    }

    /// A collinear ring yields a zero-area polygon.
    /// Its bounding box is legal (though zero height), with degeneracy is detected by validation.
    @Test
    void flatRing () {
        CPolygon flat = new CPolygon(new double[] {0, 0, 1, 0, 2, 0, 0, 0});
        CBox box = flat.toBox();
        assertEquals(0, box.maxLat - box.minLat);
        assertFalse(flat.validate());
    }

    /// Some general geometry traits are declared on CGeometry for all subtypes.
    /// A point has a zero-extent bounding box and converts to a JTS point.
    /// A line string converts to a JTS line string, feeding the shared validation.
    @Test
    void generalGeometryTraits () {
        CPoint point = new CPoint(5, 6);
        CBox box = point.toBox();
        assertEquals(5, box.minLon);
        assertEquals(6, box.maxLat);
        Point jts = point.toJts();
        assertEquals(5, jts.getX());
        assertEquals(6, jts.getY());
        assertTrue(point.validate());
        CLineString line = new CLineString(new double[] {1, 2, 3, 4});
        LineString jtsLine = line.toJts();
        assertEquals(2, jtsLine.getNumPoints());
        assertTrue(line.validate());
        assertEquals(2, line.nPoints());
        assertEquals(3, line.getLon(1));
        assertEquals(4, line.getLat(1));
    }

    /// A multipolygon with fewer than two polygons cannot be constructed.
    /// We represent a single-member source multipolygon as a plain CPolygon instead.
    @Test
    void tooFewPolygons () throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new CMultiPolygon(new CPolygon[0]));
        CPolygon square = CPolygon.fromJts((org.locationtech.jts.geom.Polygon) wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))"));
        assertThrows(IllegalArgumentException.class, () -> new CMultiPolygon(new CPolygon[] {square}));
    }

    @Test
    void singleMemberMultiPolygonUnwraps () throws Exception {
        Geometry jts = wkt("MULTIPOLYGON (((0 0, 1 0, 1 1, 0 1, 0 0)))");
        CPolygonal compact = CPolygonal.fromJts((MultiPolygon) jts);
        assertInstanceOf(CPolygon.class, compact);
        assertTrue(new PointInPolygonTester(compact).contains(0.5, 0.5));
    }


}
