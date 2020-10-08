package com.conveyal.data.geobuf;

import geobuf.Geobuf;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Encode features to GeoBuf.
 */
public class GeobufEncoder {
    private static final Logger LOG = LoggerFactory.getLogger(GeobufEncoder.class);

    private OutputStream outputStream;

    public final int precision;

    /** What to multiply floating point values by to get desired precision */
    public final long precisionMultiplier;

    public GeobufEncoder(OutputStream outputStream, int precision) {
        this.outputStream = outputStream;
        this.precision = precision;
        this.precisionMultiplier = (long) Math.pow(10, precision);
    }

    public void writeFeatureCollection (Collection<GeobufFeature> featureCollection) throws IOException {
        outputStream.write(makeFeatureCollection(featureCollection).toByteArray());
    }

    public Geobuf.Data makeFeatureCollection (Collection<GeobufFeature> featureCollection) {
        Geobuf.Data.Builder data = Geobuf.Data.newBuilder()
                .setPrecision(this.precision)
                // TODO don't hardwire
                .setDimensions(2);

        Geobuf.Data.FeatureCollection.Builder fc = Geobuf.Data.FeatureCollection.newBuilder();

        // deduplicate keys
        List<String> keys = new ArrayList<>();

        featureCollection.stream()
                .map(f -> this.makeFeature(f, keys))
                .forEach(fc::addFeatures);

        fc.addAllValues(Collections.emptyList());
        fc.addAllCustomProperties(Collections.emptyList());

        data.setFeatureCollection(fc);
        data.addAllKeys(keys);

        return data.build();
    }

    private Geobuf.Data.Feature makeFeature (GeobufFeature feature, List<String> keys) {
        Geobuf.Data.Feature.Builder feat = Geobuf.Data.Feature.newBuilder()
                .setGeometry(geomToGeobuf(feature.geometry));

        for (Map.Entry<String, Object> e : feature.properties.entrySet()) {
            // TODO store keys separately from features
            Geobuf.Data.Value.Builder val = Geobuf.Data.Value.newBuilder();

            Object featVal = e.getValue();

            if (featVal instanceof String)
                val.setStringValue((String) featVal);
            else if (featVal instanceof Boolean)
                val.setBoolValue((Boolean) featVal);
            else if (featVal instanceof Integer) {
                int keyInt = (Integer) featVal;
                if (keyInt >= 0)
                    val.setPosIntValue(keyInt);
                else
                    val.setNegIntValue(keyInt);
            }
            else if (featVal instanceof Long) {
                long keyLong = (Long) featVal;
                if (keyLong >= 0)
                    val.setPosIntValue(keyLong);
                else
                    val.setNegIntValue(keyLong);
            }
            else if (featVal instanceof Double || featVal instanceof Float)
                val.setDoubleValue(((Number) featVal).doubleValue());
            else {
                // TODO serialize to JSON
                LOG.warn("Unable to save object of type {} to geobuf, falling back on toString. Deserialization will not work as expected.", featVal.getClass());
                val.setStringValue(featVal.toString());
            }

            int keyIdx = keys.indexOf(e.getKey());
            if (keyIdx == -1) {
                synchronized (keys) {
                    keyIdx = keys.size();
                    keys.add(e.getKey());
                }
            }

            // properties is a jagged array of [key index, value index, . . .]
            feat.addProperties(keyIdx);
            feat.addProperties(feat.getValuesCount());
            feat.addValues(val);
        }

        if (feature.id != null) {
            feat.setId(feature.id);
            feat.clearIntId();
        }
        else {
            feat.setIntId(feature.numericId);
            feat.clearId();
        }

        return feat.build();
    }

    public Geobuf.Data.Geometry geomToGeobuf (Geometry geometry) {
        if (geometry instanceof Point)
            return pointToGeobuf((Point) geometry);
        else if (geometry instanceof Polygon)
            return polyToGeobuf((Polygon) geometry);
        else if (geometry instanceof MultiPolygon)
            return multiPolyToGeobuf((MultiPolygon) geometry);
        else
            throw new UnsupportedOperationException("Unsupported geometry type " + geometry.getGeometryType());
    }

    public Geobuf.Data.Geometry pointToGeobuf(Point point) {
        return Geobuf.Data.Geometry.newBuilder()
                .setType(Geobuf.Data.Geometry.Type.POINT)
                .addCoords((long) (point.getX() * precisionMultiplier))
                .addCoords((long) (point.getY() * precisionMultiplier))
                .build();
    }

    public Geobuf.Data.Geometry multiPolyToGeobuf (MultiPolygon poly) {
        Geobuf.Data.Geometry.Builder builder = Geobuf.Data.Geometry.newBuilder()
                .setType(Geobuf.Data.Geometry.Type.MULTIPOLYGON);

        // first we specify the number of polygons
        builder.addLengths(poly.getNumGeometries());

        for (int i = 0; i < poly.getNumGeometries(); i++) {
            Polygon p = (Polygon) poly.getGeometryN(i);
            // how many rings there are
            builder.addLengths(p.getNumInteriorRing() + 1);

            Stream<LineString> interiorRings = IntStream.range(0, p.getNumInteriorRing())
                    .<LineString>mapToObj(p::getInteriorRingN);

            Stream.concat(Stream.of(p.getExteriorRing()), interiorRings)
                    .forEach(r -> addRing(r, builder));
        }

        return builder.build();
    }

    public Geobuf.Data.Geometry polyToGeobuf (Polygon poly) {
        Geobuf.Data.Geometry.Builder builder = Geobuf.Data.Geometry.newBuilder()
                .setType(Geobuf.Data.Geometry.Type.POLYGON);

        Stream<LineString> interiorRings = IntStream.range(0, poly.getNumInteriorRing())
                .mapToObj(poly::getInteriorRingN);

        Stream.concat(Stream.of(poly.getExteriorRing()), interiorRings)
                .forEach(r -> addRing(r, builder));

        return builder.build();
    }

    /** Add a ring to a builder */
    private void addRing(LineString r, Geobuf.Data.Geometry.Builder builder) {
        // skip last point, same as first
        builder.addLengths(r.getNumPoints() - 1);

        long x, y, prevX = 0, prevY = 0;

        // last point is same as first, skip
        for (int i = 0; i < r.getNumPoints() - 1; i++) {
            // delta code
            Coordinate coord = r.getCoordinateN(i);
            // note that roundoff errors do not accumulate
            x = (long) (coord.x * precisionMultiplier);
            y = (long) (coord.y * precisionMultiplier);
            builder.addCoords(x - prevX);
            builder.addCoords(y - prevY);
            prevX = x;
            prevY = y;
        }
    }

    public void close () throws IOException {
        outputStream.close();
    }

    public static class GeobufFeatureSerializer implements Serializer<GeobufFeature>, Serializable {
        public final int precision;

        private transient GeobufEncoder encoder;

        public GeobufFeatureSerializer (int precision) {
            this.precision = precision;
        }

        @Override
        public void serialize(DataOutput dataOutput, GeobufFeature geobufFeature) throws IOException {
            GeobufEncoder enc = getEncoder();
            // This could be more efficient, we're wrapping a single feature in a feature collection. But that way the key/value
            // serialization all works.
            Geobuf.Data feat = enc.makeFeatureCollection(Arrays.asList(geobufFeature));
            byte[] data = feat.toByteArray();
            dataOutput.writeInt(data.length);
            dataOutput.write(data);
        }

        @Override
        public GeobufFeature deserialize(DataInput dataInput, int i) throws IOException {
            int len = dataInput.readInt();
            byte[] feat = new byte[len];
            dataInput.readFully(feat);
            Geobuf.Data data = Geobuf.Data.parseFrom(feat);

            return new GeobufFeature(data.getFeatureCollection().getFeatures(0), data.getKeysList(), Math.pow(10, data.getPrecision()));
        }

        @Override
        public int fixedSize () {
            return -1;
        }

        /** get the geobuf encoder, lazy initializing if needed. JVM should inline this function */
        private GeobufEncoder getEncoder () {
            if (encoder == null) {
                synchronized (this) {
                    if (encoder == null) {
                        encoder = new GeobufEncoder(new ByteArrayOutputStream(), precision);
                    }
                }
            }

            return encoder;
        }
    }
}
