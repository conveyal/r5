package com.conveyal.gtfs.geom;

import com.fasterxml.jackson.core.JsonToken;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Reads a GeoJSON FeatureCollection of polygonal features, passing each usable feature to a
/// sink. Features with problems are skipped and counted for later logging or recording.
///
/// Geometry is parsed in a single streaming pass with essentially no heap allocation beyond the
/// packed coordinate arrays of the output. Parse state lives on the stack and in a few scratch
/// buffers reused across features. This is building out and evaluating pure streaming GeoJSON
/// parsing for possible reuse on much larger inputs such as demographic data layers. In such uses,
/// features might be flushed to a database or rasterized into another structure as they are read,
/// without ever being retained on the heap.
public class GeoJsonStreamer extends JsonStreamer {

    public enum IdSource {
        /// IDs are in a top-level field of each feature, which is standard for GTFS and GeoJSON.
        FEATURE_ID,
        /// IDs are in a property named id. This is a better source for layers drawn by users in
        /// GIS software because top-level IDs may be automatically overwritten by software.
        ID_PROPERTY
    }

    /// Each usable feature is passed to this function after it is read.
    /// The name may be null. The geometry is always a structurally correct 2D Polygon or
    /// MultiPolygon, but it may still fail validate().
    @FunctionalInterface
    public interface FeatureSink {
        void accept (String id, String name, CPolygonal geometry);
    }

    private final IdSource idSource;

    /// The number of numeric feature-level ids interpreted as strings (GTFS does not allow numbers).
    private int numericFeatureIds = 0;

    /// The name of the property holding each feature's display name,
    /// or null when the consumer has no use for names.
    private final String nameProperty;

    /// Coordinates of the ring currently being read, as packed (lon, lat) doubles.
    private final TDoubleArrayList ringScratch = new TDoubleArrayList();

    /// Completed rings of the polygon currently being read.
    private final List<double[]> rings = new ArrayList<>();

    /// Completed polygons of the geometry currently being read.
    private final List<CPolygon> polygons = new ArrayList<>();

    /// Set when the coordinates of the geometry currently being read do not form clean 2D rings.
    /// This allows consumption to continue, skipping the entire feature.
    private boolean malformed = false;

    /// An enum of the ways a feature can fail to be usable.
    /// These are used to tally skipped features, allowing a single error message per problem type.
    public enum FeatureProblem {
        /// The feature has no string id, which this loader requires to allow references.
        MISSING_ID,
        /// The feature has no geometry or a null geometry.
        MISSING_GEOMETRY,
        /// The geometry declares a non-polygonal GeoJSON type such as LineString.
        UNSUPPORTED_GEOMETRY_TYPE,
        /// The coordinates do not have the structure of a two-dimensional Polygon or MultiPolygon.
        MALFORMED_GEOMETRY,
        /// The geometry parsed cleanly but is not valid, for example zero area or crossing rings.
        INVALID_GEOMETRY
    }

    /// The number of features skipped due to each problem type.
    /// The no-entry value is zero, so problems never encountered return a count of zero.
    private final TObjectIntMap<FeatureProblem> problemCounts = new TObjectIntHashMap<>();

    /// Any unsupported GeoJSON type names that were encountered (UNSUPPORTED_GEOMETRY_TYPE).
    /// A limited number of values are retained to make resulting messages more informative.
    private final Set<String> unsupportedTypes = new LinkedHashSet<>();

    public GeoJsonStreamer (InputStream inputStream, IdSource idSource, String nameProperty) {
        super(inputStream);
        this.idSource = idSource;
        this.nameProperty = nameProperty;
    }

    public void stream (FeatureSink sink) {
        try {
            advance(JsonToken.START_OBJECT);
            while (jp.nextToken() != JsonToken.END_OBJECT) {
                switch (fieldName()) {
                    case "type" -> expectString("FeatureCollection");
                    case "crs" -> checkCrs();
                    case "features" -> {
                        expect(JsonToken.START_ARRAY);
                        while (jp.nextToken() != JsonToken.END_ARRAY) readOneFeature(sink);
                    }
                    default -> skipValue();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GeoJSON.", e);
        }
    }

    /// Consume one GeoJSON feature starting at the current (not next) token, passing it to the
    /// sink if it is usable and otherwise tallying why it was skipped.
    private void readOneFeature (FeatureSink sink) throws IOException {
        expect(JsonToken.START_OBJECT);
        String featureId = null;
        String propertyId = null;
        String name = null;
        CPolygonal geometry = null;
        boolean geometrySeen = false;
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (fieldName()) {
                case "type" -> expectString("Feature");
                case "id" -> featureId = featureIdValue();
                case "properties" -> {
                    // Tolerate "properties": null, which is legal GeoJSON.
                    if (jp.currentToken() == JsonToken.VALUE_NULL) continue;
                    expect(JsonToken.START_OBJECT);
                    while (jp.nextToken() != JsonToken.END_OBJECT) {
                        String property = fieldName();
                        if (property.equals("id")) {
                            propertyId = stringValueOrNull();
                        } else if (property.equals(nameProperty)) { // False for a null nameProperty.
                            name = stringValueOrNull();
                        } else {
                            skipValue();
                        }
                    }
                }
                case "geometry" -> {
                    geometry = streamOnePolygonal();
                    geometrySeen = true;
                }
                default -> skipValue();
            }
        }
        String id = (idSource == IdSource.FEATURE_ID) ? featureId : propertyId;
        if (id == null) {
            tallyProblem(FeatureProblem.MISSING_ID);
            return;
        }
        if (geometry == null) {
            if (!geometrySeen) tallyProblem(FeatureProblem.MISSING_GEOMETRY);
            return;
        }
        sink.accept(id, name, geometry);
    }

    /// Returns the current feature-level id as a String.
    /// GTFS disallows numeric IDs so we perform this conversion and flag it to warn the user.
    private String featureIdValue () throws IOException {
        if (jp.currentToken().isNumeric()) {
            numericFeatureIds += 1;
            return jp.getText();
        }
        return stringValueOrNull();
    }

    public int numericFeatureIdCount () {
        return numericFeatureIds;
    }

    /// Consumes the current value and returns it as a String, or null when it is any other type.
    private String stringValueOrNull () throws IOException {
        if (jp.currentToken() == JsonToken.VALUE_STRING) {
            return stringValue();
        }
        skipValue();
        return null;
    }

    /// Current GeoJSON (RFC 7946) has no crs member and coordinates are always unprojected WGS84,
    /// but the obsolete 2008 spec allowed declaring a named coordinate reference system. Accept an
    /// absent or null crs, or one whose name is equivalent to WGS84 as served by common tools.
    /// Reject anything else, since coordinates in another system would be silently misread as
    /// (lon, lat) degrees.
    private void checkCrs () throws IOException {
        if (jp.currentToken() == JsonToken.VALUE_NULL) return; // The 2008 spec: null means default.
        expect(JsonToken.START_OBJECT);
        String name = null;
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            if ("properties".equals(fieldName())) {
                if (jp.currentToken() == JsonToken.VALUE_NULL) continue;
                expect(JsonToken.START_OBJECT);
                while (jp.nextToken() != JsonToken.END_OBJECT) {
                    if ("name".equals(fieldName())) {
                        name = stringValue();
                    } else {
                        skipValue();
                    }
                }
            } else {
                skipValue();
            }
        }
        // Accept the names for WGS84 lon/lat degrees: CRS:84, OGC CRS84 urns, EPSG:4326 and its
        // urn (whose official axis order we, like most software, ignore), and plain WGS84.
        String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
        boolean wgs84 = upper.endsWith("CRS84") || upper.endsWith("CRS:84")
                || upper.contains("4326") || upper.equals("WGS84");
        if (!wgs84) {
            throw new IllegalArgumentException(
                "GeoJSON must contain unprojected WGS84 coordinates, but declares CRS: " + name);
        }
    }

    /// Record one skipped feature. Sinks may also call this to reject a delivered feature.
    public void tallyProblem (FeatureProblem problem) {
        problemCounts.adjustOrPutValue(problem, 1, 1);
    }

    public int problemCount (FeatureProblem problem) {
        return problemCounts.get(problem);
    }

    /// The total number of features skipped for any reason.
    public int skippedFeatureCount () {
        int total = 0;
        for (int count : problemCounts.values()) total += count;
        return total;
    }

    /// One human-readable phrase summarizing all recorded problems.
    public String describeProblems () {
        List<String> parts = new ArrayList<>();
        for (FeatureProblem problem : FeatureProblem.values()) {
            int count = problemCounts.get(problem);
            if (count == 0) continue;
            String part = count + " " + problem.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (problem == FeatureProblem.UNSUPPORTED_GEOMETRY_TYPE) {
                part += " (" + String.join(", ", unsupportedTypes) + ")";
            }
            parts.add(part);
        }
        return String.join(", ", parts);
    }

    /// Consume one GeoJSON geometry object starting at the current token. Returns the polygonal
    /// geometry, or null when the geometry is not a clean two-dimensional Polygon or MultiPolygon.
    /// A MultiPolygon containing a single polygon is unwrapped into a Polygon.
    /// When null is returned, the object will still be consumed in full from the token stream,
    /// leaving the parser ready for the following feature. The reason it was skipped is recorded.
    ///
    /// GeoJSON spec section 3.1.6: a Polygon's coordinates MUST be an array of linear ring
    /// coordinate arrays, the first exterior and the rest holes. Section 3.1.7: a MultiPolygon's
    /// coordinates MUST be an array of Polygon coordinate arrays. The two types therefore differ
    /// only in nesting depth, so coordinates are parsed optimistically without relying on seeing
    /// the type field first, and the observed depth determines the result. The type field serves
    /// only to reject named non-polygonal types. Unknown fields such as bbox are ignored.
    private CPolygonal streamOnePolygonal () throws IOException {
        if (jp.currentToken() == JsonToken.VALUE_NULL) {
            tallyProblem(FeatureProblem.MISSING_GEOMETRY);
            return null;
        }
        expect(JsonToken.START_OBJECT);
        ringScratch.resetQuick();
        rings.clear();
        polygons.clear();
        malformed = false;
        String type = null;
        int depth = 0;
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (fieldName()) {
                case "type" -> type = stringValue();
                case "coordinates" -> {
                    expect(JsonToken.START_ARRAY);
                    depth = streamCoordinateArrays(1);
                }
                default -> skipValue();
            }
        }
        boolean polygonalType =
              (type == null || "Polygon".equals(type) || "MultiPolygon".equals(type));
        if (!polygonalType) {
            tallyProblem(FeatureProblem.UNSUPPORTED_GEOMETRY_TYPE);
            if (unsupportedTypes.size() < 4) unsupportedTypes.add(type);
            return null;
        }
        if (malformed || (depth != 3 && depth != 4)) {
            tallyProblem(FeatureProblem.MALFORMED_GEOMETRY);
            return null;
        }
        if (polygons.size() == 1) return polygons.get(0);
        return new CMultiPolygon(polygons.toArray(new CPolygon[0]));
    }

    /// Consume one JSON array at the current token and everything nested inside it.
    /// Numbers are appended to the scratch buffer for rings. When an array closes and its children
    /// were positions, a ring is completed. When an array closes and its children were
    /// rings, a polygon is completed. Polygon and MultiPolygon coordinates are both handled.
    /// Returns the top-level array's nesting depth. This will be 1 for a position, 2 for a ring,
    /// 3 for coordinates of a polygon, and 4 for coordinates of a multipolygon.
    /// Structural problems such as positions without exactly two numbers, children of differing
    /// depths, values that are not numbers, or empty or excessively deep nesting set the malformed
    /// flag rather than throwing. Consumption continues and the entire enclosing feature is skipped.
    /// This method is called recursively, which is somewhat more readable than an iterative form
    /// and does not sacrifice robustness due to a hard recursion limit at 4 levels.
    private int streamCoordinateArrays (int level) throws IOException {
        if (level > 4) {
            malformed = true;
            skipValue();
            return 1;
        }
        int numbers = 0;
        int childDepth = 0;
        while (jp.nextToken() != JsonToken.END_ARRAY) {
            if (jp.currentToken() == JsonToken.START_ARRAY) {
                int depth = streamCoordinateArrays(level + 1);
                if (childDepth != 0 && depth != childDepth) malformed = true;
                childDepth = depth;
            } else if (jp.currentToken().isNumeric()) {
                ringScratch.add(jp.getDoubleValue());
                numbers += 1;
            } else {
                malformed = true;
                skipValue();
            }
        }
        if (childDepth == 0) {
            // This array held numbers or nothing. It should be a 2D coordinate position.
            if (numbers != 2) malformed = true;
            return 1;
        }
        if (numbers > 0) malformed = true; // Mixed numbers and arrays at one level.
        if (childDepth == 1) finishRing();
        if (childDepth == 2) finishPolygon();
        return childDepth + 1;
    }

    /// An array just closed and it contained positions. Pack the scratch buffer into a ring.
    private void finishRing () {
        rings.add(ringScratch.toArray());
        ringScratch.resetQuick();
    }

    /// An array just closed and it contained rings. Bundle the rings into a polygon. If the
    /// ring constraints checked in the CPolygon constructor fail, the geometry is malformed.
    private void finishPolygon () {
        try {
            polygons.add(CPolygon.fromRings(rings));
        } catch (IllegalArgumentException e) {
            malformed = true;
        }
        rings.clear();
    }

}
