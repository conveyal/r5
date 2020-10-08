package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.trove.TIntAugmentedList;
import gnu.trove.list.TByteList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TByteArrayList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.Serializable;

/**
 * Store a large number of vertices in parallel arrays, providing some abstraction to view them as Vertex objects.
 * This class is a workaround for Java's lack of compound value types.
 * It is the equivalent of an array of Vertex structs, but transposed to be a column store.
 * Not because the work we're doing operates frequently on entire columns,
 * just because the quirks of Java make parallel arrays more memory efficient than lists of objects.
 * It would be nice if this kind of functionality was built into the language but it's not too terrible to hand-roll it.
 * It does also have the advantage of allowing a field to be dropped entirely (its array field is null) which is
 * more space-efficient than a struct array when a field may be missing/null in every element.
 */
public class VertexStore implements Serializable {

    public static final double FIXED_FACTOR = 1e7; // we could just reuse the constant from osm-lib Node.
    // TODO direct mm_per_fixed_degree conversion, work entirely in mm and fixed degrees.

    public TIntList fixedLats;
    public TIntList fixedLons;
    public TByteList vertexFlags;

    public VertexStore (int initialSize) {
        fixedLats = new TIntArrayList(initialSize);
        fixedLons = new TIntArrayList(initialSize);
        vertexFlags = new TByteArrayList(initialSize);
    }

    /**
     * Add a vertex, specifying its coordinates in double-precision floating point degrees.
     * @param lat latitude in floating point degrees
     * @param lon longitude in floating point degrees
     * @return the index of the new vertex.
     */
    public int addVertex (double lat, double lon) {
        return addVertexFixed(floatingDegreesToFixed(lat), floatingDegreesToFixed(lon));
    }

    /**
     * Add a vertex, specifying its coordinates in fixed-point lat and lon.
     * @return the index of the new vertex.
     */
    public int addVertexFixed (int fixedLat, int fixedLon) {
        int vertexIndex = vertexFlags.size();
        fixedLats.add(fixedLat);
        fixedLons.add(fixedLon);
        vertexFlags.add((byte)0);
        return vertexIndex;
    }

    public class Vertex {

        public int index;

        /** Must call advance() before use, e.g. while (vertex.advance()) {...} */
        public Vertex () {
            this (-1);
        }

        public Vertex (int index) {
            this.index = index;
        }

        /** @return whether this cursor is still within the list (there is a vertex to read). */
        public boolean advance () {
            index += 1;
            return index < getVertexCount();
        }

        public void seek (int index) {
            this.index = index;
        }

        public void setLat(double lat) {
            fixedLats.set(index, (int)(lat * FIXED_FACTOR));
        }

        public void setLon(double lon) {
            fixedLons.set(index, (int) (lon * FIXED_FACTOR));
        }

        public boolean getFlag(VertexFlag flag) {
            return (vertexFlags.get(index) & flag.flag) != 0;
        }

        public void setFlag(VertexFlag flag) {
            vertexFlags.set(index, (byte)(vertexFlags.get(index) | flag.flag));
        }

        public double getLat() {
            return fixedLats.get(index) / FIXED_FACTOR;
        }

        public double getLon() {
            return fixedLons.get(index) / FIXED_FACTOR;
        }

        public int getFixedLat() {
            return fixedLats.get(index);
        }

        public int getFixedLon() {
            return fixedLons.get(index);
        }

        public Point getJTSPointFloating () {
            return GeometryUtils.geometryFactory.createPoint(getJTSCoordinateFloating());
        }

        public Coordinate getJTSCoordinateFloating () {
            return new Coordinate(getLon(), getLat());
        }

    }

    public Vertex getCursor() {
        return new Vertex();
    }

    public Vertex getCursor(int index) {
        return new Vertex(index);
    }

    public static int floatingDegreesToFixed(double degrees) {
        return (int)(degrees * FIXED_FACTOR);
    }

    public static double fixedDegreesToFloating(int fixed) {
        return fixed / FIXED_FACTOR;
    }

    /** Return the number of vertices currently stored in this VertexStore. */
    public int getVertexCount() {
        return vertexFlags.size();
    }

    /**
     * Used when converting fixed-point latitude and longitude to floating-point from Split.
     * The parameter datatype is double even though it is a fixed-point representation.
     */
    public static double fixedDegreesToFloating(double fixed) {
        return fixed / FIXED_FACTOR;
    }

    /**
     * Given a Geometry in fixed-point latitude and longitude, return a copy converted to floating point latitude and
     * longitude.
     */
    public static Geometry fixedDegreeGeometryToFloating(Geometry fixedGeometry) {
        Geometry wgsResult = (Geometry)fixedGeometry.clone();
        wgsResult.apply((CoordinateFilter) c -> {
            c.x = fixedDegreesToFloating(c.x); c.y = fixedDegreesToFloating(c.y);
        });
        return wgsResult;
    }

    /** Convert a JTS envelope to fixed-point degrees. */
    public static Envelope envelopeToFixed(Envelope env) {
        return new Envelope(
                floatingDegreesToFixed(env.getMinX()),
                floatingDegreesToFixed(env.getMaxX()),
                floatingDegreesToFixed(env.getMinY()),
                floatingDegreesToFixed(env.getMaxY())
        );
    }

    public enum VertexFlag {

        TRAFFIC_SIGNAL(0), // This intersection has a traffic signal
        PARK_AND_RIDE(1),
        BIKE_SHARING(2);

        // TODO Add Stop and split vertex flags. This allows blocking U-turns at splits and eliminating the special stop finder visitor

        /** In each enum value this field should contain an integer with only a single bit switched on. */
        public final int flag;

        /** Conveniently create a unique integer flag pattern for each of the enum values. */
        private VertexFlag (int bitNumber) {
            flag = 1 << bitNumber;
        }

    }

    /**
     * Makes a copy of this VertexStore that can have vertices added to it, but cannot otherwise be modified.
     * This is done efficiently by wrapping the existing lists holding the various vertex characteristics.
     *
     * We don't use clone() to make sure every field is explicitly copied below, avoiding any unintentional
     * shallow-copying of collections or referenced data structures.
     */
    public VertexStore extendOnlyCopy() {
        VertexStore copy = new VertexStore(0);
        copy.fixedLats = new TIntAugmentedList(this.fixedLats);
        copy.fixedLons = new TIntAugmentedList(this.fixedLons);
        // This is a deep copy, we should do an extend-copy but need a new TByteArrayList class for that.
        // I would like to make sure the extend-only system works well before proliferating classes of this kind.
        copy.vertexFlags = new TByteArrayList(vertexFlags);
        return copy;
    }

}
