package com.conveyal.r5.streets;

import com.conveyal.modes.StreetMode;
import com.conveyal.osmlib.Node;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.rastercost.CostField;
import com.conveyal.util.DirectionUtils;
import com.conveyal.util.GeometryUtils;
import com.conveyal.util.P2;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Class that serves as a cursor: points to a single edge in this store, and can be moved to other indexes.
 */
public class Edge {
    private static final Logger LOG = LoggerFactory.getLogger(Edge.class);

    public int edgeIndex = -1;
    public int pairIndex = -1;
    public boolean isBackward = true;

    private EdgeStore edgeStore;

    /**
     * An Edge object that can be moved around in the EdgeStore to get a view of any edge in the graph.
     * This object is not pointing at any edge upon creation, so you'll need to call seek() on it to select an edge.
     */
    public Edge(EdgeStore edgeStore) {
        this.edgeStore = edgeStore;
    }

    /**
     * An Edge object that can be moved around in the EdgeStore to get a view of any edge in the graph.
     */
    public Edge(EdgeStore edgeStore, int pos) {
        this.edgeStore = edgeStore;
        this.seek(pos);
    }

    /**
     * Move the cursor forward one edge.
     *
     * @return true if we have not advanced past the end of the list (there is an edge at the new position).
     */
    public boolean advance() {
        edgeIndex += 1;
        pairIndex = edgeIndex / 2;
        isBackward = !isBackward;
        return edgeIndex < edgeStore.nEdges();
    }

    /**
     * Move the cursor back one edge.
     */
    public void retreat() {
        edgeIndex--;
        pairIndex = edgeIndex / 2;
        isBackward = !isBackward;
    }

    /**
     * Jump to a specific edge number.
     */
    public void seek(int pos) {
        if (pos < 0)
            throw new ArrayIndexOutOfBoundsException("Attempt to seek to negative edge number");
        if (pos >= edgeStore.nEdges())
            throw new ArrayIndexOutOfBoundsException("Attempt to seek beyond end of edge store");

        edgeIndex = pos;
        // divide and multiply by two are fast bit shifts
        pairIndex = edgeIndex / 2;
        isBackward = (pairIndex * 2) != edgeIndex;
    }

    public int getFromVertex() {
        return isBackward ? edgeStore.toVertices.get(pairIndex) : edgeStore.fromVertices.get(pairIndex);
    }

    public int getToVertex() {
        return isBackward ? edgeStore.fromVertices.get(pairIndex) : edgeStore.toVertices.get(pairIndex);
    }

    /**
     * NOTE that this will have an effect on both edges in the bidirectional edge pair.
     */
    public void setToVertex(int toVertexIndex) {
        if (isBackward) {
            edgeStore.fromVertices.set(pairIndex, toVertexIndex);
        } else {
            edgeStore.toVertices.set(pairIndex, toVertexIndex);
        }
    }

    public boolean getFlag(EdgeFlag flag) {
        return (edgeStore.flags.get(edgeIndex) & flag.flag) != 0;
    }

    public void setFlag(EdgeFlag flag) {
        edgeStore.flags.set(edgeIndex, edgeStore.flags.get(edgeIndex) | flag.flag);
    }

    public void clearFlag(EdgeFlag flag) {
        edgeStore.flags.set(edgeIndex, edgeStore.flags.get(edgeIndex) & ~(flag.flag)); // TODO verify logic
    }

    public EdgeStore getEdgeStore() {
        return edgeStore;
    }

    /**
     * This is inefficient, it's just a stopgap until we refactor to eliminate the need for Sets of EdgeFlag
     */
    public EnumSet<EdgeFlag> getFlags() {
        EnumSet<EdgeFlag> ret = EnumSet.noneOf(EdgeFlag.class);
        for (EdgeFlag flag : EdgeFlag.values()) {
            if (getFlag(flag)) {
                ret.add(flag);
            }
        }
        return ret;
    }

    public void setFlags(Set<EdgeFlag> flagSet) {
        for (EdgeFlag flag : flagSet) {
            setFlag(flag);
        }
    }

    public short getSpeed() {
        return edgeStore.speeds.get(edgeIndex);
    }

    /**
     * @return the car speed on this edge, taking live traffic updates into account if requested (though that's not
     * yet implemented)
     */
    public float getCarSpeedMetersPerSecond() {
        return (float) ((edgeStore.speeds.get(edgeIndex) / 100.));
    }

    public float getSpeedKph() {
        return (float) ((edgeStore.speeds.get(edgeIndex) / 100.) * 3.6);
    }

    // This is really ugly, we should store speeds in mm/sec or something. There is an outstanding PR for this.
    public void setSpeedKph(double speedKph) {
        edgeStore.speeds.set(edgeIndex, (short) (speedKph / 3.6 * 100));
    }

    /**
     * Note, this is expecting weird units and should be hidden. Use setSpeedKph.
     */
    public void setSpeed(short speed) {
        edgeStore.speeds.set(edgeIndex, speed);
    }

    public int getLengthMm() {
        return edgeStore.lengths_mm.get(pairIndex);
    }

    /**
     * Copy the flags and speeds from the supplied Edge cursor into this one.
     * Doesn't copy OSM ids since those are set when edge is created.
     * This is a hack and should be done some other way.
     */
    public void copyPairFlagsAndSpeeds(Edge other) {
        final int foreEdge = pairIndex * 2;
        final int backEdge = foreEdge + 1;
        final int otherForeEdge = other.pairIndex * 2;
        final int otherBackEdge = otherForeEdge + 1;
        final EdgeStore otherStore = other.getEdgeStore();
        edgeStore.flags.set(foreEdge, otherStore.flags.get(otherForeEdge));
        edgeStore.flags.set(backEdge, otherStore.flags.get(otherBackEdge));
        edgeStore.speeds.set(foreEdge, otherStore.speeds.get(otherForeEdge));
        edgeStore.speeds.set(backEdge, otherStore.speeds.get(otherBackEdge));
        edgeStore.streetClasses.set(pairIndex, otherStore.streetClasses.get(other.pairIndex));
    }

    public void copyPairGeometry(Edge other) {
        edgeStore.geometries.set(pairIndex, other.getEdgeStore().geometries.get(other.pairIndex));
    }

    /**
     * @return length of edge in meters
     */
    public double getLengthM() {
        return getLengthMm() / 1000.0;
    }

    /**
     * Set the length for the current edge pair (always the same in both directions).
     */
    public void setLengthMm(int millimeters) {
        edgeStore.lengths_mm.set(pairIndex, millimeters);
    }

    public boolean isBackward() {
        return isBackward;
    }

    public boolean isForward() {
        return !isBackward;
    }

    /**
     * Calculate the speed appropriately given the ProfileRequest and traverseMode and the current wall clock time.
     * Note: this is not strictly symmetrical, because in a forward search we get the speed based on the
     * time we enter this edge, whereas in a reverse search we get the speed based on the time we exit
     * the edge.
     * <p>
     * If driving speed is based on max edge speed. (or from traffic if traffic is supported)
     * <p>
     * Otherwise speed is based on wanted walking, cycling speed provided in ProfileRequest.
     */
    public float calculateSpeed(ProfileRequest options, StreetMode traverseStreetMode) {
        if (traverseStreetMode == null) {
            // Do we really want to do this? Why not just let the NPE happen if the parameter is missing?
            return Float.NaN;
        } else if (traverseStreetMode == StreetMode.CAR) {
            // TODO: apply speed based on traffic information if switched on in the request
            return getCarSpeedMetersPerSecond();
        }
        return options.getSpeedForMode(traverseStreetMode);
    }

    /**
     * Cut the packed array of intermediate coordinates in two at the specified segment index, non-destructively
     * (i.e. returning copies). The original array of the edge's intermediate coordinates does not include the first
     * and last points of the edge (which are supplied by vertices), so the segment at index N ends at
     * intermediate point N, which occupies array indexes N2 and N2+1 in the packed array. Intermediate points
     * before the split point (which is on the specified segment), ending with the beginning of the split
     * segment, should be in the first return array. Intermediate points after the split point, starting with the
     * end of the split segment, should be in the second return array. The first or second return array can be
     * empty if we are splitting at the first or last segment respectively, or if there are no intermediate
     * coordinates.
     *
     * @return Pair with element a being int[] of intermediate coordinates before split, and element b being
     * int[] of intermediate coordinates after split.
     */
    public P2<int[]> splitGeometryAfter(int segment) {
        int[] preSplit = EdgeStore.EMPTY_INT_ARRAY;
        int[] postSplit = EdgeStore.EMPTY_INT_ARRAY;
        // Original packed array of edge's intermediate coordinates
        int[] original = edgeStore.geometries.get(pairIndex);
        if (original.length > 0) {
            if (segment > 0) {
                preSplit = Arrays.copyOfRange(original, 0, segment * 2);
            }
            if (segment * 2 < original.length) {
                postSplit = Arrays.copyOfRange(original, segment * 2, original.length);
            }
        }
        return new P2<>(preSplit, postSplit);
    }

    /**
     * Set intermediate coordinates directly with packed array
     *
     * @param coordinates Packed lists of lat, lon, lat, lon... as fixed-point integers
     */
    public void setGeometry(int[] coordinates) {
        edgeStore.geometries.set(pairIndex, coordinates);
        calculateAngles();
    }

    /**
     * Set intermediate coordinates from OSM nodes
     */
    public void setGeometry(List<Node> nodes) {
        // The same empty int array represents all straight-line edges.
        if (nodes.size() <= 2) {
            edgeStore.geometries.set(pairIndex, EdgeStore.EMPTY_INT_ARRAY);
            //Angles also need to be calculated here since when splitting edges some edges currently loose geometry
            //and otherwise angles would be incorrect
            calculateAngles();
            return;
        }
        if (isBackward) {
            LOG.warn("Setting a forward geometry on a back edge.");
        }
        // Create a geometry, which will be used for both forward and backward edge.
        int nIntermediatePoints = nodes.size() - 2;
        // Make a packed list of all coordinates between the endpoint intersections.
        int[] intermediateCoords = new int[nIntermediatePoints * 2];
        int i = 0;
        for (Node node : nodes.subList(1, nodes.size() - 1)) {
            intermediateCoords[i++] = node.fixedLat;
            intermediateCoords[i++] = node.fixedLon;
        }
        edgeStore.geometries.set(pairIndex, intermediateCoords);

        //This is where angles for most edges is calculated
        calculateAngles();

    }

    public void setStreetClass(StreetClass streetClass) {
        edgeStore.streetClasses.set(pairIndex, streetClass.code);
    }

    public byte getStreetClassCode() {
        return edgeStore.streetClasses.get(pairIndex);
    }

    /**
     * Reads edge geometry and calculates in and out angle
     * <p>
     * Angles are saved as binary radians clockwise from North.
     * 0 is 0
     * -180° = -128
     * 180° = -180° = -128
     * 178° = 127
     * <p>
     * First and last angles are calculated between first/last and point that is at
     * least 10 m from it according to line distance
     *
     * @see DirectionUtils
     * <p>
     * TODO: calculate angles without converting to the Linestring and back
     */
    public void calculateAngles() {
        LineString geometry = getGeometry();

        byte inAngleRad = DirectionUtils.getFirstAngleBrads(geometry);
        edgeStore.inAngles.set(pairIndex, inAngleRad);

        byte outAngleRad = DirectionUtils.getLastAngleBrads(geometry);
        edgeStore.outAngles.set(pairIndex, outAngleRad);
    }

    public int getOutAngle() {
        int angle;
        if (isBackward()) {
            angle = DirectionUtils.bradsToDegree((byte) (edgeStore.inAngles.get(pairIndex) - DirectionUtils.m180));
            return angle;
        } else {
            angle = DirectionUtils.bradsToDegree(edgeStore.outAngles.get(pairIndex));
            return angle;
        }
    }

    public int getInAngle() {
        int angle;
        if (isBackward()) {
            angle = DirectionUtils.bradsToDegree((byte) (edgeStore.outAngles.get(pairIndex) - DirectionUtils.m180));
            return angle;
        } else {
            angle = DirectionUtils.bradsToDegree(edgeStore.inAngles.get(pairIndex));
            return angle;
        }
    }


    /**
     * Returns LineString geometry of edge in floating point geographic coordinates.
     * Uses from/to vertices for first/last node and nodes from geometries for middle nodes
     * <p>
     * TODO: it might be better idea to return just list of coordinates
     *
     * @return
     */
    public LineString getGeometry() {
        int[] coords = edgeStore.geometries.get(pairIndex);
        //Size is 2 (from and to vertex) if there are no intermediate vertices
        int size = coords == EdgeStore.EMPTY_INT_ARRAY ? 2 :
                //division with two since coordinates are in same array saved as lat, lon,lat etc.
                (coords.length / 2) + 2;
        Coordinate[] c = new Coordinate[size];

        VertexStore.Vertex fromVertex = edgeStore.vertexStore.getCursor(getFromVertex());
        VertexStore.Vertex toVertex = edgeStore.vertexStore.getCursor(getToVertex());

        double fromVertexLon = fromVertex.getLon();
        double fromVertexLat = fromVertex.getLat();
        double toVertexLon = toVertex.getLon();
        double toVertexLat = toVertex.getLat();

        boolean reverse = isBackward();

        double firstCoorLon = reverse ? toVertexLon : fromVertexLon;
        double firstCoorLat = reverse ? toVertexLat : fromVertexLat;
        double lastCoorLon = reverse ? fromVertexLon : toVertexLon;
        double lastCoorLat = reverse ? fromVertexLat : toVertexLat;

        // NB it initially appears that we are reversing the from and to vertex twice, but keep in mind that
        // getFromVertex returns the from vertex of the edge _pair_ rather than that of a particular edge.
        // This is the from vertex when we are on a forward edge, and the to vertex when we are on a back edge.
        c[0] = new Coordinate(firstCoorLon, firstCoorLat);
        if (coords != null) {
            for (int i = 1; i < c.length - 1; i++) {
                int ilat = coords[(i - 1) * 2];
                int ilon = coords[(i - 1) * 2 + 1];
                c[i] = new Coordinate(ilon / GeometryUtils.FIXED_DEGREES_FACTOR, ilat / GeometryUtils.FIXED_DEGREES_FACTOR);
            }
        }
        c[c.length - 1] = new Coordinate(lastCoorLon, lastCoorLat);
        LineString out = GeometryUtils.geometryFactory.createLineString(c);
        if (reverse)
            out = (LineString) out.reverse();
        return out;
    }

    /**
     * Call a function on every segment in this edge's geometry.
     * Always iterates forward over the geometry, whether we are on a forward or backward edge.
     */
    public void forEachSegment(EdgeStore.SegmentConsumer segmentConsumer) {
        VertexStore.Vertex vertex = edgeStore.vertexStore.getCursor(edgeStore.fromVertices.get(pairIndex));
        int prevFixedLat = vertex.getFixedLat();
        int prevFixedLon = vertex.getFixedLon();
        int[] intermediates = edgeStore.geometries.get(pairIndex);
        int s = 0;
        int i = 0;
        while (i < intermediates.length) {
            int fixedLat = intermediates[i++];
            int fixedLon = intermediates[i++];
            segmentConsumer.consumeSegment(s, prevFixedLat, prevFixedLon, fixedLat, fixedLon);
            prevFixedLat = fixedLat;
            prevFixedLon = fixedLon;
            s++;
        }
        vertex.seek(edgeStore.toVertices.get(pairIndex));
        segmentConsumer.consumeSegment(s, prevFixedLat, prevFixedLon, vertex.getFixedLat(), vertex.getFixedLon());
    }


    /**
     * Call a function for every point on this edge's geometry, including the beginning end end points.
     * Always iterates forward over the geometry, whether we are on a forward or backward edge.
     */
    public void forEachPoint(EdgeStore.PointConsumer pointConsumer) {
        VertexStore.Vertex vertex = edgeStore.vertexStore.getCursor(edgeStore.fromVertices.get(pairIndex));
        int p = 0;
        pointConsumer.consumePoint(p++, vertex.getFixedLat(), vertex.getFixedLon());
        int[] intermediates = edgeStore.geometries.get(pairIndex);
        int i = 0;
        while (i < intermediates.length) {
            pointConsumer.consumePoint(p++, intermediates[i++], intermediates[i++]);
        }
        vertex.seek(edgeStore.toVertices.get(pairIndex));
        pointConsumer.consumePoint(p, vertex.getFixedLat(), vertex.getFixedLon());
    }

    /**
     * @return an envelope around the whole edge geometry, in fixed-point WGS84 degrees.
     */
    public Envelope getEnvelope() {
        Envelope envelope = new Envelope();
        forEachPoint((p, fixedLat, fixedLon) -> envelope.expandToInclude(fixedLon, fixedLat));
        return envelope;
    }

    @Override
    public String toString() {
        String base = String.format("Edge from %d to %d. Length %f meters, speed %f kph.",
                getFromVertex(), getToVertex(), getLengthMm() / 1000D, getSpeedKph());
        return base + getFlagsAsString();
    }

    public String getFlagsAsString() {
        StringBuilder sb = new StringBuilder();
        for (EdgeFlag flag : EdgeFlag.values()) {
            if (getFlag(flag)) {
                sb.append(" ");
                sb.append(flag.toString());
            }
        }
        return sb.toString();
    }

    /**
     * Creates text label from permissions
     * <p>
     * It looks like walk,bike,car or none.
     * If any mode of transport is not allowed it is missing in return value.
     *
     * @return
     */
    public String getPermissionsAsString() {
        StringJoiner sb = new StringJoiner(",");
        sb.setEmptyValue("none");
        if (getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
            sb.add("walk");
        }
        if (getFlag(EdgeFlag.ALLOWS_BIKE)) {
            sb.add("bike");
        }
        if (getFlag(EdgeFlag.ALLOWS_CAR)) {
            sb.add("car");
        }
        return sb.toString();
    }

    /**
     * Returns only enumSet of permission flags (CAR, BICYCLE, WALKING)
     *
     * @return
     */
    public EnumSet<EdgeFlag> getPermissionFlags() {
        EnumSet<EdgeFlag> edgePermissionFlags = EnumSet.noneOf(EdgeFlag.class);
        for (EdgeFlag permissionFlag : EdgeStore.PERMISSION_FLAGS) {
            if (getFlag(permissionFlag)) {
                edgePermissionFlags.add(permissionFlag);
            }
        }
        return edgePermissionFlags;
    }

    public int getEdgeIndex() {
        return edgeIndex;
    }

    /**
     * @return whether this edge may be modified. It may not be modified if it is part of a baseline graph that has
     * been extended by a scenario. It may be modified if it's part of a baseline graph in the process of being
     * built, or if it was created as part of the scenario being applied.
     */
    public boolean isMutable() {
        return edgeIndex >= edgeStore.firstModifiableEdge;
    }

    /**
     * Set the flags for all on-street modes of transportation to "true", so that any mode can traverse this edge.
     */
    public void allowAllModes() {
        setFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
        setFlag(EdgeFlag.ALLOWS_BIKE);
        setFlag(EdgeFlag.ALLOWS_CAR);
        setFlag(EdgeFlag.ALLOWS_WHEELCHAIR);
    }

    /**
     * Set the flags for all on-street modes of transportation to "false", so no modes can traverse this edge.
     */
    public void disallowAllModes() {
        clearFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
        clearFlag(EdgeFlag.ALLOWS_BIKE);
        clearFlag(EdgeFlag.ALLOWS_CAR);
        clearFlag(EdgeFlag.ALLOWS_WHEELCHAIR);
    }

    public boolean allowsStreetMode(StreetMode mode) {
        if (mode == StreetMode.WALK) {
            return getFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
        }
        if (mode == StreetMode.BICYCLE) {
            return getFlag(EdgeFlag.ALLOWS_BIKE);
        }
        if (mode == StreetMode.CAR) {
            return getFlag(EdgeFlag.ALLOWS_CAR);
        }
        throw new RuntimeException("Supplied mode not recognized.");
    }

    /**
     * Set flags on the current edge allowing traversals by searches with any of the given streetModes enabled.
     */
    public void allowStreetModes(Iterable<StreetMode> streetModes) {
        for (StreetMode streetMode : streetModes) {
            allowStreetMode(streetMode);
        }
    }

    /**
     * Set a flag on the current edge allowing traversals by searches with the given streetMode enabled.
     */
    public void allowStreetMode(StreetMode mode) {
        if (mode == StreetMode.WALK) {
            setFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
        } else if (mode == StreetMode.BICYCLE) {
            setFlag(EdgeFlag.ALLOWS_BIKE);
        } else if (mode == StreetMode.CAR) {
            setFlag(EdgeFlag.ALLOWS_CAR);
        } else {
            throw new RuntimeException("Mode not recognized:" + mode);
        }
    }

    public long getOSMID() {
        return edgeStore.osmids.get(pairIndex);
    }

    public void setWalkTimeFactor(double walkTimeFactor) {
        edgeStore.edgeTraversalTimes.setWalkTimeFactor(edgeIndex, walkTimeFactor);
    }

    public void setBikeTimeFactor(double bikeTimeFactor) {
        edgeStore.edgeTraversalTimes.setBikeTimeFactor(edgeIndex, bikeTimeFactor);
    }

    public Map<String, Object> attributesForDisplay() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", edgeIndex); // Map UI component seems to group edges by this ID to span tile boundaries.
        map.put("streetClass", (int) edgeStore.streetClasses.get(pairIndex)); // Serialization cannot handle Byte, cast it.
        // map.put("osmId", getOSMID());
        map.put("speedKph", getSpeedKph());
        map.put("lengthM", getLengthM());
        // FIXME we should employ a method like com.conveyal.r5.labeling.LevelOfTrafficStressLabeler.ltsToInt for this
        int lts = getFlag(EdgeFlag.BIKE_LTS_1) ? 1 :
                getFlag(EdgeFlag.BIKE_LTS_2) ? 2 :
                        getFlag(EdgeFlag.BIKE_LTS_3) ? 3 : 4;
        map.put("lts", lts);
        map.put("pedestrian", getFlag(EdgeFlag.ALLOWS_PEDESTRIAN));
        map.put("bike", getFlag(EdgeFlag.ALLOWS_BIKE));
        map.put("car", getFlag(EdgeFlag.ALLOWS_CAR));
        if (edgeStore.edgeTraversalTimes != null) {
            map.put("walkTimeFactor", edgeStore.edgeTraversalTimes.getWalkTimeFactor(edgeIndex));
            map.put("bikeTimeFactor", edgeStore.edgeTraversalTimes.getBikeTimeFactor(edgeIndex));
        }
        if (edgeStore.costFields != null) {
            for (CostField costField : edgeStore.costFields) {
                map.put(costField.getDisplayKey(), costField.getDisplayValue(edgeIndex));
            }
        }
        return map;
    }

}
