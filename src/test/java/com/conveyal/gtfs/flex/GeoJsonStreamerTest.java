package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.GTFSError;
import com.conveyal.gtfs.error.UnsupportedFlexError;
import com.conveyal.gtfs.geom.CMultiPolygon;
import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.gtfs.geom.CPolygonWithHoles;
import com.conveyal.gtfs.geom.CPolygonal;
import com.conveyal.gtfs.geom.PointInPolygonTester;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of reading GTFS-Flex location geometries from GeoJSON: both polygonal types, either
/// field order within a geometry object, per-feature skipping of anything that is not a clean 2D
/// polygonal geometry (with one summary error per file), and hard failure only on JSON that is
/// malformed or not a FeatureCollection.
class GeoJsonStreamerTest {

    private static final String SQUARE = "[[0,0],[1,0],[1,1],[0,1],[0,0]]";

    private static final String FAR_SQUARE = "[[3,0],[4,0],[4,1],[3,1],[3,0]]";

    private static final String HOLE = "[[0.25,0.25],[0.75,0.25],[0.75,0.75],[0.25,0.75],[0.25,0.25]]";

    /// Interpolates the arguments into the format string, then replaces every apostrophe with a
    /// double quote, so JSON literals can be written without quote escaping. This follows a
    /// pattern in Jackson's own test suite.
    private static String json (String format, Object... args) {
        return String.format(format, args).replace('\'', '"');
    }

    private static String feature (String id, String geometry) {
        return json("{'type':'Feature','id':'%s','properties':{'stop_name':'%s'},'geometry':%s}",
            id, id, geometry);
    }

    private static String featureCollection (String... features) {
        return json("{'type':'FeatureCollection','features':[%s]}", String.join(",", features));
    }

    /// Load the given GeoJSON string into a feed using the same code path that loads GTFS locations.geojson.
    private static GTFSFeed load (String json) {
        GTFSFeed feed = GTFSFeed.newWritableInMemory();
        FlexLocationStreamer.loadLocationsJson(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), feed);
        return feed;
    }

    /// Load a single GeoJSON feature with the given geometry and return the geometry of that
    /// feature as our internal polygonal types. Asserts there were no errors during the operation.
    private static CPolygonal read (String geometry) {
        GTFSFeed feed = load(featureCollection(feature("zone", geometry)));
        assertEquals(0, feed.errors.size(), "No errors expected for a supported geometry.");
        assertEquals(1, feed.locations.size());
        return feed.locations.get("zone").geometry;
    }

    /// Load a single GeoJSON feature with the given geometry, expecting it to be skipped.
    /// Returns the summary error message.
    private static String skipMessage (String geometry) {
        GTFSFeed feed = load(featureCollection(feature("zone", geometry)));
        assertTrue(feed.locations.isEmpty(), "The feature should be skipped.");
        return summaryError(feed);
    }

    private static String summaryError (GTFSFeed feed) {
        assertEquals(1, feed.errors.size(), "Exactly one summary error should be recorded per file.");
        GTFSError error = feed.errors.iterator().next();
        assertInstanceOf(UnsupportedFlexError.class, error);
        return error.getMessage();
    }

    @Test
    void polygon () {
        CPolygonal geometry = read(json("{'type':'Polygon','coordinates':[%s]}", SQUARE));
        assertInstanceOf(CPolygon.class, geometry);
        assertFalse(geometry instanceof CPolygonWithHoles);
        assertTrue(new PointInPolygonTester(geometry).contains(0.5, 0.5));
    }

    @Test
    void polygonWithHole () {
        CPolygonal geometry = read(json("{'type':'Polygon','coordinates':[%s,%s]}", SQUARE, HOLE));
        assertInstanceOf(CPolygonWithHoles.class, geometry);
        PointInPolygonTester tester = new PointInPolygonTester(geometry);
        assertTrue(tester.contains(0.1, 0.1));
        assertFalse(tester.contains(0.5, 0.5));
    }

    @Test
    void multiPolygon () {
        CPolygonal geometry = read(json("{'type':'MultiPolygon','coordinates':[[%s],[%s]]}", SQUARE, FAR_SQUARE));
        assertInstanceOf(CMultiPolygon.class, geometry);
        PointInPolygonTester tester = new PointInPolygonTester(geometry);
        assertTrue(tester.contains(0.5, 0.5));
        assertTrue(tester.contains(3.5, 0.5));
        assertFalse(tester.contains(2, 0.5));
    }

    /// A MultiPolygon containing a single polygon should be unwrapped to a plain polygon.
    @Test
    void singleMemberMultiPolygonUnwraps () {
        CPolygonal geometry = read(json("{'type':'MultiPolygon','coordinates':[[%s]]}", SQUARE));
        assertInstanceOf(CPolygon.class, geometry);
        assertFalse(geometry instanceof CMultiPolygon);
    }

    /// GeoJSON is not prescriptive about the order of fields, so coordinates may precede type.
    @Test
    void coordinatesBeforeType () {
        CPolygonal geometry = read(json("{'coordinates':[[%s],[%s]],'type':'MultiPolygon'}", SQUARE, FAR_SQUARE));
        assertInstanceOf(CMultiPolygon.class, geometry);
    }

    /// Unknown members of a geometry object such as a GeoJSON bbox should be ignored.
    @Test
    void unknownGeometryFieldsIgnored () {
        CPolygonal geometry = read(json("{'type':'Polygon','bbox':[0,0,1,1],'coordinates':[%s]}", SQUARE));
        assertInstanceOf(CPolygon.class, geometry);
    }

    @Test
    void unsupportedTypeSkipsFeature () {
        String message = skipMessage(json("{'type':'LineString','coordinates':%s}", SQUARE));
        assertTrue(message.contains("LineString"), message);
    }

    /// If coordinate nesting indicates a LineString, the feature should be skipped even if the type says Polygon.
    @Test
    void wrongNestingSkipsFeature () {
        String message = skipMessage(json("{'coordinates':%s,'type':'Polygon'}", SQUARE));
        assertTrue(message.contains("malformed"), message);
    }

    /// Positions must be two-dimensional coordinates.
    @Test
    void malformedPositionSkipsFeature () {
        String message = skipMessage(json("{'type':'Polygon','coordinates':[[[0,0,0],[1,0],[1,1],[0,0]]]}"));
        assertTrue(message.contains("malformed"), message);
    }

    /// A polygon can parse cleanly yet be invalid, like this zero-area one whose ring is collinear.
    /// It should be skipped and reported.
    @Test
    void invalidGeometrySkipsFeature () {
        String message = skipMessage(json("{'type':'Polygon','coordinates':[[[0,0],[1,0],[2,0],[0,0]]]}"));
        assertTrue(message.contains("invalid"), message);
    }

    @Test
    void nullGeometrySkipsFeature () {
        String message = skipMessage("null");
        assertTrue(message.contains("missing"), message);
    }

    /// One bad feature should not affect the others. The two valid zones should load, with one
    /// summary error reporting a skipped feature.
    @Test
    void mixedGeometriesKeepValidZones () {
        GTFSFeed feed = load(featureCollection(
            feature("good", json("{'type':'Polygon','coordinates':[%s]}", SQUARE)),
            feature("bad", json("{'type':'LineString','coordinates':%s}", SQUARE)),
            feature("far", json("{'type':'Polygon','coordinates':[%s]}", FAR_SQUARE))));
        assertEquals(2, feed.locations.size(), "Both valid zones should survive the bad one.");
        assertTrue(feed.locations.containsKey("good"));
        assertTrue(feed.locations.containsKey("far"));
        String message = summaryError(feed);
        assertTrue(message.contains("1 feature"), message);
        assertTrue(message.contains("LineString"), message);
    }

    /// A feature with no id should be skipped like one with a bad geometry.
    /// No trip could reference it. All other valid zones in the file should still load.
    @Test
    void missingIdSkipsFeature () {
        GTFSFeed feed = load(featureCollection(
            feature("good", json("{'type':'Polygon','coordinates':[%s]}", SQUARE)),
            json("{'type':'Feature','properties':{},'geometry':{'type':'Polygon','coordinates':[%s]}}", FAR_SQUARE)));
        assertEquals(1, feed.locations.size(), "The zone with an id should survive.");
        assertTrue(feed.locations.containsKey("good"));
        String message = summaryError(feed);
        assertTrue(message.contains("missing id"), message);
    }

    /// A top-level id field on a feature is legal GeoJSON but not legal GTFS. It should be
    /// interpreted as its literal string form, with one warning-level error message.
    @Test
    void numericFeatureIdInterpretedAsString () {
        String feature = json("{'type':'Feature','id':42,'properties':{},"
            + "'geometry':{'type':'Polygon','coordinates':[%s]}}", SQUARE);
        GTFSFeed feed = load(featureCollection(feature));
        assertTrue(feed.locations.containsKey("42"), "The numeric id should become a string.");
        String message = summaryError(feed);
        assertTrue(message.contains("interpreted as strings"), message);
    }

    /// If the legacy GeoJSON crs member is present, it should be accepted as long as it names a
    /// WGS84-equivalent system. RFC 7946 files have no crs member at all. Any other coordinate
    /// system should abort loading, since its coordinates would be silently misread as degrees.
    @Test
    void crsChecked () {
        String feature = feature("zone", json("{'type':'Polygon','coordinates':[%s]}", SQUARE));
        String wgs84 = json("{'type':'FeatureCollection','crs':{'type':'name','properties':"
            + "{'name':'urn:ogc:def:crs:OGC:1.3:CRS84'}},'features':[%s]}", feature);
        assertEquals(1, load(wgs84).locations.size(), "A WGS84-equivalent named crs should be accepted.");
        String projected = json("{'type':'FeatureCollection','crs':{'type':'name','properties':"
            + "{'name':'EPSG:3857'}},'features':[%s]}", feature);
        assertThrows(RuntimeException.class, () -> load(projected),
            "A projected crs should abort loading.");
    }

    /// Truly malformed JSON, or a file that is not a FeatureCollection, aborts loading.
    @Test
    void malformedJsonFails () {
        assertThrows(RuntimeException.class, () -> load(json("{'type':'FeatureCollection','features':[{")));
        assertThrows(RuntimeException.class, () -> load(json("{'type':'Directory','features':[]}")));
    }

}
