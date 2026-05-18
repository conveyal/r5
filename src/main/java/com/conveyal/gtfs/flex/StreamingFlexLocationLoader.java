package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.transit.GtfsTransferLoader;
import com.conveyal.r5.transit.TransitLayer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.OSM_ONLY;

/// This should be broken apart into a streaming loader for GeoJSON and a subclass or wrapper that
/// applies this to GTFS Flex locations specifically.
///
/// R5 IndexedPolygonCollection and ModificationPolygon use the JTS Polygonal interface (which is
/// not a Geometry). We end up replicating a lot of GeoTools GeoJSON loading capabilities, but can
/// sidestep the heavy GeoTools abstraction and serialization difficulties.
///
/// ModificationPolygon associates a Polygonal with a unique ID and a rider-facing name.
/// Consider that there are typically only a few zones per file and all coordinates must be
/// loaded in memory for conversion to a JTS geometry, so streaming is not a critical optimization.
/// But a streaming loader is efficient and relevant here, and will be heavily reusable.
///
/// Initially we were calling into GeoJsonModule (the Jackson parsing module from BeDataDriven)
/// which calls GeometryDeserializer.parseGeometry() and is registered with JsonUtil.objectMapper.
/// But that tree-parses one geometry at a time, not a FeatureCollection, so we needed to stream
/// parse the outer structure anyway and read fragments with with JsonUtil.objectMapper.readValue();
public abstract class StreamingFlexLocationLoader {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Main method for use in development and testing. Load a flex feed and build
    /// This should be turned into a real Java test in the final work product.
    public static void main (String[] args) throws Exception {
        String zipFileName = args[0];
        GTFSFeed gtfs = GTFSFeed.readOnlyTempFileFromGtfs(zipFileName);
        TransitLayer transitLayer = new TransitLayer();
        transitLayer.loadFromGtfs(gtfs, new GtfsTransferLoader(transitLayer, OSM_ONLY));
        gtfs.close();
    }

    /// Though this loads a GTFS table, it does not use Entity.Loader because that assumes files are
    /// CSV and their file names end in .txt. Having List as the return type buffers all the objects
    /// in memory which reduces the advantages of streaming. In a later refinement, it may be better
    /// to supply a consumer method to the load function, storing row results in MapDB one by one.
    public static List<FlexLocation> loadLocationsJson (ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("locations.geojson");
        if (entry == null) {
            LOG.info("Feed does not have locations.geojson specifying flex zones.");
            return null;
        }
        InputStream inStream = zip.getInputStream(entry);
        // GTFS reference says pick-up and drop-off zones in locations.geojson are always polygonal.
        // The features are allowed to be a mix of Polygon and MultiPolygon types.
        // The top level must be a FeatureCollection and every feature must have a string ID.
        // GTFS allows the UTF byte order mark in files, so we need to handle it.
        // JSON allows only UTF-8/16/32, which the Jackson streaming JsonParser auto-detects:
        // ByteSourceJsonBootstrapper.constructParser calls detectEncoding which has BOM handling.
        // The Jackson streaming JSON API sacrifices readability for speed and memory so is not
        // always ideal. Here the JSON structure is simple enough that it works cleanly.
        // When reading into the tree model instead of streaming, objectMapper.readTree calls
        // createParser which calls constructParser indirectly benefitting from its BOM handling.
        List<FlexLocation> result = new ArrayList<>();
        JsonParser jp = JsonUtilities.objectMapper.getFactory().createParser(inStream);
        expectNext(jp, JsonToken.START_OBJECT);
        while (jp.nextToken() != null && jp.currentToken() != JsonToken.END_OBJECT) {
            switch (expectCurrentFieldName(jp)) {
                case "type" -> expectNextString(jp, "FeatureCollection");
                case "features" -> {
                    expectNext(jp, JsonToken.START_ARRAY);
                    while (jp.nextToken() != null && jp.currentToken() != JsonToken.END_ARRAY) {
                        FlexLocation location = readOneLocation(jp);
                        result.add(location);
                    }
                }
            }
        }
        return result;
    }

    private static FlexLocation readOneLocation (JsonParser jp) throws IOException {
        // JsonNode featureObject = readTree...
        expectCurrent(jp, JsonToken.START_OBJECT);
        // load into variables to construct a record, or into fields of an object
        String id = null;
        String name = null;
        CPolygon cPolygon = null;
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (expectCurrentFieldName(jp)) {
                case "type" -> expectNextString(jp, "Feature");
                case "id" -> id = expectNextString(jp);
                case "properties" -> {
                    expectNext(jp, JsonToken.START_OBJECT);
                    // Tree is read starting at current token, not next token.
                    JsonNode propsNode = jp.readValueAsTree();
                    // Unlike get, path returns missing node objects to allow chaining.
                    name = propsNode.path("stop_name").asText();
                    // read stopName. wait, why is the name of a location (which is a zone) called a stopName?
                }
                case "geometry" -> cPolygon = streamOnePolygon(jp);
            }
        }
        cPolygon.validate();
        return new FlexLocation(id, name, null, cPolygon);
        // return new FlexLocation(id, name, null, JTSConverter.fromJts(geometry));
    }

    private static void expect (JsonToken actual, JsonToken expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Input does not appear to be valid GeoJSON.");
        }
    }

    private static void expectAny (JsonToken actual, JsonToken... expected) {
        if (Arrays.stream(expected).noneMatch(token -> actual == token)) {
            throw new IllegalArgumentException("Input does not appear to be valid GeoJSON.");
        }
    }

    /// Enforces an expectation that the next (not current) token is of a specific kind.
    private static void expectNext (JsonParser jp, JsonToken token) throws IOException {
        expect(jp.nextToken(), token);
    }

    /// Enforces an expectation that the supplied token is either a float or an int.
    private static void expectNumber (JsonToken token) throws IOException {
        if (token != JsonToken.VALUE_NUMBER_FLOAT && token != JsonToken.VALUE_NUMBER_INT) {
            throw new IllegalArgumentException("Expected JSON integer or floating point number.");
        }
    }

    /// Enforces an expectation that the current (not next) token is of a specific kind.
    private static void expectCurrent (JsonParser jp, JsonToken token) throws IOException {
        expect(jp.currentToken(), token);
    }

    /// Enforces an expectation that the current (not next) token is an object field with any name.
    /// @return the name of the field
    private static String expectCurrentFieldName (JsonParser jp) throws IOException {
        expectCurrent(jp, JsonToken.FIELD_NAME);
        return jp.currentName();
    }

    /// Enforces an expectation that the next (not current) token is a String and returns it.
    private static String expectNextString (JsonParser jp) throws IOException {
        expectNext(jp, JsonToken.VALUE_STRING);
        return jp.getText();
    }

    /// Enforces an expectation that the current (not next) token is a number and returns it as a double.
    private static double expectCurrentDouble (JsonParser jp) throws IOException {
        expectNumber(jp.currentToken());
        return jp.getDoubleValue();
    }

    /// Enforces an expectation that the next (not current) token is a number and returns it as a double.
    private static double expectNextDouble (JsonParser jp) throws IOException {
        expectNumber(jp.nextToken());
        return jp.getDoubleValue();
    }

    /// Enforces an expectation that the next (not current) token is a specific String value.
    private static void expectNextString (JsonParser jp, String value) throws IOException {
        if (!value.equals(expectNextString(jp))) {
            throw new IllegalArgumentException("Unexpected text value.");
        }
    }

    private static void expectCurrentNonNull (JsonParser jp) {
        if (jp.currentToken() == null) {
            throw new IllegalStateException("Unexpected end of input in JSON.");
        }
    }

    private static void iterateObjectFields () {
        // TODO helper method to apply a function to every field name and value
    }

    /// Consumes one entire JSON array of GeoJSON positions (array of two-element arrays) from the
    /// supplied parser. Begins consuming at the curren (not next) token for use in loops.
    /// Returns it as a packed array of doubles in (x, y) i.e. (lon, lat) order.
    public static double[] streamOnePositionArray (JsonParser jp) throws IOException {
        TDoubleList packedCoords = new TDoubleArrayList();
        expectCurrent(jp, JsonToken.START_ARRAY);
        while (jp.nextToken() != JsonToken.END_ARRAY) {
            expectCurrent(jp, JsonToken.START_ARRAY);
            while (jp.nextToken() != JsonToken.END_ARRAY) {
                packedCoords.add(expectCurrentDouble(jp));
                packedCoords.add(expectNextDouble(jp));
            }
        }
        return packedCoords.toArray();
    }

    /// Consumes an array of N arrays of GeoJSON positions (like [[[],[]], [[],[]]]) from the
    /// supplied Jackson JsonParser. Begins consuming at the next token, not the current one.
    public static List<double[]> streamPositionArrays (JsonParser jp) throws IOException {
        List<double[]> arrays = new ArrayList<>();
        expectNext(jp, JsonToken.START_ARRAY);
        while (jp.nextToken() != JsonToken.END_ARRAY) {
            arrays.add(streamOnePositionArray(jp));
        }
        return arrays;
    }

    /// From GeoJSON spec section 3.1.6. (Polygon):
    /// For type "Polygon", the "coordinates" member MUST be an array of linear ring coordinate
    /// arrays. For Polygons with more than one of these rings, the first MUST be the exterior ring,
    /// and any others MUST be interior rings. The exterior ring bounds the surface, and the
    /// interior rings (if present) bound holes within the surface.
    public static CPolygon streamOnePolygon (JsonParser jp) throws IOException {
        CPolygon polygon = null;
        expectNext(jp, JsonToken.START_OBJECT);
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (expectCurrentFieldName(jp)) {
                case "coordinates" -> polygon = CPolygon.fromRings(streamPositionArrays(jp));
                case "type" -> expectNextString(jp, "Polygon");
                default -> throw new IllegalArgumentException("Unrecognized field in GeoJSON geometry.");
            }
        }
        // With BeDataDriven JTS parser: GEOMETRY_PARSER.geometryFromJson(geomNode);
        return polygon;
    }

}
