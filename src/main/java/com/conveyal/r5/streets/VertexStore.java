package com.conveyal.r5.streets;

import com.conveyal.r5.trove.TIntAugmentedList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 *
 */
public class VertexStore implements Serializable {

    public static final double FIXED_FACTOR = 1e7; // we could just reuse the constant from osm-lib Node.
    // TODO direct mm_per_fixed_degree conversion, work entirely in mm and fixed degrees.

    public int nVertices = 0;
    public TIntList fixedLats;
    public TIntList fixedLons;
    public List<EnumSet<VertexFlag>> vertexFlags; // TODO change this to use one big int array of bit flags.

    public VertexStore (int initialSize) {
        fixedLats = new TIntArrayList(initialSize);
        fixedLons = new TIntArrayList(initialSize);
        vertexFlags = new ArrayList<>(initialSize);
    }

    /**
     * Add a vertex, specifying its coordinates in double-precision floating point degrees.
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
        int vertexIndex = nVertices++;
        fixedLats.add(fixedLat);
        fixedLons.add(fixedLon);
        vertexFlags.add(EnumSet.noneOf(VertexFlag.class));
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
            return index < nVertices;
        }

        public void seek (int index) {
            this.index = index;
        }

        public void setLat(double lat) {
            fixedLats.set(index, (int)(lat * FIXED_FACTOR));
        }

        public void setLon(double lon) {
            fixedLons.set(index, (int)(lon * FIXED_FACTOR));
        }

        public void setLatLon(double lat, double lon) {
            setLat(lat);
            setLon(lon);
        }

        public boolean getFlag (VertexFlag flag) {
            return vertexFlags.get(index).contains(flag);
        }

        public void setFlag (VertexFlag flag) {
            vertexFlags.get(index).add(flag);
        }

        public void clearFlag (VertexFlag flag) {
            vertexFlags.get(index).remove(flag);
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


    //Used when converting fixed latitude and longitude to floating from Split
    //It is in double type even though it is fixed
    public static double fixedDegreesToFloating(double fixed) {
        return fixed / FIXED_FACTOR;
    }


    public enum VertexFlag {
        /** this intersection has a traffic signal */
        TRAFFIC_SIGNAL
    }

    /**
     * Makes a copy of this VertexStore that can have vertices added to it, but cannot otherwise be modified.
     * This is done efficiently by wrapping the existing lists holding the various vertex characteristics.
     */
    public VertexStore extendOnlyCopy() {
        VertexStore copy = new VertexStore(100);
        copy.nVertices = 0;
        copy.fixedLats = new TIntAugmentedList(this.fixedLats);
        copy.fixedLons = new TIntAugmentedList(this.fixedLons);
        copy.vertexFlags = null; // TODO change this to use one big int array of bit flags.
        return copy;
    }

}
