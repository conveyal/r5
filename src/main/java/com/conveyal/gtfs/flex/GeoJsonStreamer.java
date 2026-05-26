package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;
import com.fasterxml.jackson.core.JsonToken;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/// Adds GeoJSON-specific methods to the JSON streamer, such as reading coordinate arrays.
public abstract class GeoJsonStreamer extends JsonStreamer {

    public GeoJsonStreamer (InputStream inputStream) {
        super(inputStream);
    }

    public void stream () {
        try {
            expectNext(JsonToken.START_OBJECT);
            while (jp.nextToken() != JsonToken.END_OBJECT) {
                switch (expectCurrentFieldName()) {
                    case "type" -> expectNextString("FeatureCollection");
                    case "features" -> {
                        expectNext(JsonToken.START_ARRAY);
                        while (jp.nextToken() != JsonToken.END_ARRAY) readOneFeature();
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GeoJSON.", e);
        }
    }

    /// Consume one GeoJSON feature starting at the current (not next) token.
    /// Currently must be overridden to efficiently parse more specific schemas.
    abstract void readOneFeature () throws IOException;

    void iterateObjectFields () {
        // TODO helper method to apply a function to every field name and value
    }

    /// Consumes one entire JSON array of GeoJSON positions (array of two-element arrays) from the
    /// supplied parser. Begins consuming at the curren (not next) token for use in loops.
    /// Returns it as a packed array of doubles in (x, y) i.e. (lon, lat) order.
    double[] streamOnePositionArray () throws IOException {
        TDoubleList packedCoords = new TDoubleArrayList();
        expectCurrent(JsonToken.START_ARRAY);
        while (jp.nextToken() != JsonToken.END_ARRAY) {
            expectCurrent(JsonToken.START_ARRAY);
            packedCoords.add(expectNextDouble());
            packedCoords.add(expectNextDouble());
            expectNext(JsonToken.END_ARRAY);
        }
        return packedCoords.toArray();
    }

    /// Consumes an array of N arrays of GeoJSON positions (like [[[],[]], [[],[]]]) from the
    /// Jackson JsonParser. Begins consuming at the next token, not the current one.
    List<double[]> streamPositionArrays () throws IOException {
        List<double[]> arrays = new ArrayList<>();
        expectNext(JsonToken.START_ARRAY);
        while (jp.nextToken() != JsonToken.END_ARRAY) {
            arrays.add(streamOnePositionArray());
        }
        return arrays;
    }

    /// Consume one GeoJSON polygon feature. GeoJSON spec section 3.1.6. (Polygon) says:
    /// For type "Polygon", the "coordinates" member MUST be an array of linear ring coordinate
    /// arrays. For Polygons with more than one of these rings, the first MUST be the exterior ring,
    /// and any others MUST be interior rings. The exterior ring bounds the surface, and the
    /// interior rings (if present) bound holes within the surface.
    CPolygon streamOnePolygon () throws IOException {
        CPolygon polygon = null;
        expectNext(JsonToken.START_OBJECT);
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (expectCurrentFieldName()) {
                case "coordinates" -> polygon = CPolygon.fromRings(streamPositionArrays());
                case "type" -> expectNextString("Polygon");
                default -> throw new IllegalArgumentException("Unrecognized field in GeoJSON polygon.");
            }
        }
        return polygon;
    }

}
