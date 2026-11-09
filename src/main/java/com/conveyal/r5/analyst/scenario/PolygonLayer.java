package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.geom.CPolygonal;
import com.conveyal.gtfs.geom.GeoJsonStreamer;
import com.conveyal.gtfs.geom.GeoJsonStreamer.IdSource;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Polygons that a modification refers to by ID, read from GeoJSON FeatureCollections. Each feature
/// must have a property named id, unique within the layer.
///
/// The polygons can come from a GeoJSON data source file, from a FeatureCollection written inline
/// in the JSON of a modification, or from both. parsing and problem reporting are identical for
/// both kinds of sources as they both pass through the same [#read] method.
///
/// Problems are collected here as messages instead of being thrown. The modification that owns the
/// layer reports them to the user as errors when it is resolved.
@JsonDeserialize(using = PolygonLayer.Deserializer.class)
@JsonSerialize(using = PolygonLayer.Serializer.class)
public class PolygonLayer {

    public final Map<String, CPolygonal> polygonsById = new HashMap<>();

    /// Messages describing features that could not be used. The layer is unusable if any exist.
    public final List<String> problems = new ArrayList<>();

    /// Add all the polygons in one GeoJSON FeatureCollection to this layer.
    ///
    /// This GeoJSON is usually a few polygons the user drew or exported from GIS. Bad features
    /// are considered fixable mistakes, so each one is recorded as a problem and the modification
    /// is rejected. This is stricter than the GTFS-Flex loader, which just ignores bad features
    /// and fails only if they are later referenced by a trip.
    public void read (GeoJsonStreamer streamer) {
        streamer.stream((id, name, geometry) -> add(id, geometry));
        if (streamer.skippedFeatureCount() > 0) {
            problems.add(String.format("%d feature(s) are not usable polygons with an id property: %s. " +
                    "Fix or remove these features from the layer.",
                    streamer.skippedFeatureCount(), streamer.describeProblems()));
        }
    }

    /// Add all polygons and problems from another layer to this one, leaving the other layer unchanged.
    public void add (PolygonLayer other) {
        other.polygonsById.forEach(this::add);
        problems.addAll(other.problems);
    }

    private void add (String id, CPolygonal geometry) {
        if (polygonsById.putIfAbsent(id, geometry) != null) {
            problems.add(String.format("More than one feature has the ID '%s'.", id));
        }
    }

    /// Reads a layer included as a GeoJSON FeatureCollection within a larger JSON document.
    /// Jackson calls this with its parser on the first token of the value. We pass that parser to
    /// the same streaming reader that is used for GeoJSON files.
    public static class Deserializer extends JsonDeserializer<PolygonLayer> {
        @Override
        public PolygonLayer deserialize (JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                context.reportInputMismatch(PolygonLayer.class,
                    "Inline polygons must be a GeoJSON FeatureCollection object. " +
                    "To use the polygons in a data source, give its ID in the dataSourceId property.");
            }
            PolygonLayer layer = new PolygonLayer();
            layer.read(new GeoJsonStreamer(parser, IdSource.ID_PROPERTY, null));
            return layer;
        }
    }

    /// Refuses to write a layer as JSON. The parsed polygons are not converted back to GeoJSON.
    /// Nothing is expected to write a parsed layer. Workers only read modifications, and the
    /// backend passes inline polygons through as generic JSON without ever parsing them into an
    /// instance of this class. If this exception is encountered, either stop writing the
    /// modification or implement writing the polygons as a GeoJSON FeatureCollection here.
    public static class Serializer extends JsonSerializer<PolygonLayer> {
        @Override
        public void serialize (PolygonLayer layer, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            provider.reportMappingProblem(
                "Polygons that have been parsed from a modification cannot be written back out as JSON.");
        }
    }

}
