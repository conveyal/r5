package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.util.LambdaCounter;
import com.vividsolutions.jts.geom.*;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import com.conveyal.r5.streets.EdgeStore.Edge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A LinkedPointSet is a PointSet that has been connected to a StreetLayer in a non-destructive, reversible way.
 * For each feature in the PointSet, we record the closest edge and the distance to the vertices at the ends of that
 * edge (like a Splice or a Sample in OTP).
 */
public class LinkedPointSet {

    private static final Logger LOG = LoggerFactory.getLogger(LinkedPointSet.class);

    /**
     * The distance we search around each PointSet point for a road to link it to.
     * FIXME 1KM is really far to walk off a street. But some places have offices in the middle of big parking lots.
     */
    public static final int MAX_OFFSTREET_WALK_METERS = 1000;

    /**
     * LinkedPointSets are long-lived and not extremely numerous, so we keep references to the objects it was built from.
     * Besides these fields are useful for later processing of LinkedPointSets.
     */
    public final PointSet pointSet;

    /**
     * We need to retain the street layer so we can look up edge endpoint vertices for the edge IDs.
     * Besides, this object is inextricably linked to one street network.
     */
    public final StreetLayer streetLayer;

    /**
     * The linkage may be different depending on what mode of travel one uses to reach the points from transit stops.
     * Along with the PointSet and StreetLayer this mode is what determines the contents of a particular LinkedPointSet.
     */
    public final StreetMode streetMode;

    /**
     * For each point, the closest edge in the street layer.
     * This is in fact the even (forward) edge ID of the closest edge pairs.
     */
    public int[] edges;

    /** For each point, distance from the initial vertex of the edge to the split point. */
    public int[] distances0_mm;

    /** For each point, distance from the final vertex of the edge to the split point. */
    public int[] distances1_mm;

    /** For each transit stop, the distances to nearby PointSet points as packed (point_index, distance) pairs. */
    public transient List<int[]> stopToPointDistanceTables;

    /** It is preferred to specify a mode when linking TODO remove this. */
    @Deprecated
    public LinkedPointSet(PointSet pointSet, StreetLayer streetLayer) {
        this(pointSet, streetLayer, null, null);
    }

    /**
     * A LinkedPointSet is a PointSet that has been pre-connected to a StreetLayer in a non-destructive, reversible way.
     * These objects are long-lived and not extremely numerous, so we keep references to the objects it was built from.
     * Besides they are useful for later processing of LinkedPointSets.
     */
    public LinkedPointSet(PointSet pointSet, StreetLayer streetLayer, StreetMode streetMode, LinkedPointSet baseLinkage) {

        LOG.info("Linking pointset to street network...");
        this.pointSet = pointSet;
        this.streetLayer = streetLayer;
        this.streetMode = streetMode;

        final int nPoints = pointSet.featureCount();
        final int nStops = streetLayer.parentNetwork.transitLayer.getStopCount();

        // The regions within which we want to link points to edges, then connect transit stops to points.
        // Null means relink and rebuild everything, but this will be constrained below if a base linkage was supplied.
        Geometry relinkZone = null, treeRebuildZone = null;

        if (baseLinkage == null) {
            edges = new int[nPoints];
            distances0_mm = new int[nPoints];
            distances1_mm = new int[nPoints];
            stopToPointDistanceTables = new ArrayList<>();
        } else {
            // The caller has supplied an existing linkage for a scenario StreetLayer's base StreetLayer.
            // We want to re-use most of that that existing linkage to reduce linking time.
            // TODO switch on assertions, they are off by default
            assert baseLinkage.pointSet == pointSet;
            assert baseLinkage.streetLayer == streetLayer.baseStreetLayer;
            assert baseLinkage.streetMode == streetMode;
            // Copy the supplied base linkage into this new LinkedPointSet.
            // The new linkage has the same PointSet as the base linkage, so the linkage arrays remain the same length
            // as in the base linkage. However, if the TransitLayer was also modified by the scneario, the stopToVertexDistanceTables
            // list might need to grow.
            edges = Arrays.copyOf(baseLinkage.edges, nPoints);
            distances0_mm = Arrays.copyOf(baseLinkage.distances0_mm, nPoints);
            distances1_mm = Arrays.copyOf(baseLinkage.distances1_mm, nPoints);
            stopToPointDistanceTables = new ArrayList<>(baseLinkage.stopToPointDistanceTables);
            // TODO We need to determine which points to re-link and which stops should have their stop-to-point tables re-built.
            // This should be all the points within the (bird-fly) linking radius of any modified edge.
            // The stop-to-vertex trees should already be rebuilt elsewhere when applying the scenario.
            // This should be all the stops within the (network or bird-fly) tree radius of any modified edge, including
            // any new stops that were created by the scenario (which will naturally fall within this distance).
            // And build trees from stops to points.
            // Even though currently the only changes to the street network are re-splitting edges to connect new
            // transit stops, we still need to re-link points and rebuild stop trees (both the trees to the vertices
            // and the trees to the points, because some existing stop-to-vertex trees might not include new splitter
            // vertices).
            relinkZone = streetLayer.scenarioEdgesBoundingGeometry(MAX_OFFSTREET_WALK_METERS);
            treeRebuildZone = streetLayer.scenarioEdgesBoundingGeometry(TransitLayer.DISTANCE_TABLE_SIZE_METERS);
        }

        // If dealing with a scenario, pad out the stop trees list from the base linkage to match the new stop count.
        // If dealing with a base network linkage, fill the stop trees list entirely with nulls.
        while (stopToPointDistanceTables.size() < nStops) stopToPointDistanceTables.add(null);

        /* First, link the points in this PointSet to specific street vertices. */
        this.linkPointsToStreets(relinkZone);

        /* Second, make a table of distances from each transit stop to the points in this PointSet. */
        this.makeStopToPointDistanceTables(treeRebuildZone);

    }

    /**
     * Associate the points in this PointSet with the street vertices at the ends of the closest street edge.
     * @param relinkZone only link points inside this geometry in FIXED POINT DEGREES, leaving all the others alone. If null, link all points.
     */
    private void linkPointsToStreets(Geometry relinkZone) {
        LambdaCounter counter = new LambdaCounter(LOG, pointSet.featureCount(), 10000,
                "Linked {} of {} PointSet points to streets.");
        // Perform linkage calculations in parallel, writing results to the shared parallel arrays.
        IntStream.range(0, pointSet.featureCount()).parallel().forEach(p -> {
            // When working with a scenario, skip all points outside the zone that could be affected by new edges.
            // The linkage from the original base StreetNetwork will be retained for these points.
            if (relinkZone != null && !relinkZone.contains(pointSet.getJTSPointFixed(p))) return;
            Split split = streetLayer.findSplit(pointSet.getLat(p), pointSet.getLon(p), MAX_OFFSTREET_WALK_METERS, streetMode);
            if (split == null) {
                edges[p] = -1;
            } else {
                edges[p] = split.edge;
                distances0_mm[p] = split.distance0_mm;
                distances1_mm[p] = split.distance1_mm;
            }
            counter.increment();
        });
        long unlinked = Arrays.stream(edges).filter(e -> e == -1).count();
        counter.done();
        LOG.info("{} points are not linked to the street network.", unlinked);
    }

    /** @return the number of linkages, which should be the same as the number of points in the PointSet. */
    public int size () {
        return edges.length;
    }

    /**
     * A functional interface for fetching the travel time to any street vertex in the transport network.
     * Note that TIntIntMap::get matches this functional interface.
     * There may be a generic IntToIntFunction library interface somewhere, but this interface provides type information
     * about what the function and its parameters mean.
     */
    @FunctionalInterface
    public static interface TravelTimeFunction {
        /**
         * @param vertexId the index of a vertex in the StreetLayer of a TransitNetwork.
         * @return the travel time to the given street vertex, or Integer.MAX_VALUE if the vertex is unreachable.
         */
        public int getTravelTime (int vertexId);
    }

    /**
     * Determine the travel time to every temporary vertex in this set.
     * The parameter is a function from street vertex indexes to elapsed travel times.
     *
     * TODO: Departure times and walking speeds should be supplied.
     * @return a list of travel times to each point in the PointSet. Integer.MAX_VALUE means a vertex was unreachable.
     */
    public PointSetTimes eval (TravelTimeFunction travelTimeForVertex) {
        int[] travelTimes = new int[edges.length];
        // Iterate over all locations in this temporary vertex list.
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        for (int i = 0; i < edges.length; i++) {
            if (edges[i] < 0) {
                travelTimes[i] = Integer.MAX_VALUE;
                continue;
            }
            edge.seek(edges[i]);
            int time0 = travelTimeForVertex.getTravelTime(edge.getFromVertex());
            int time1 = travelTimeForVertex.getTravelTime(edge.getToVertex());

            // TODO apply walk speed
            if (time0 != Integer.MAX_VALUE) {
                time0 += distances0_mm[i] / 1000;
            }
            if (time1 != Integer.MAX_VALUE) {
                time1 += distances1_mm[i] / 1000;
            }
            travelTimes[i] = time0 < time1 ? time0 : time1;
        }
        return new PointSetTimes (pointSet, travelTimes);
    }

    /**
     * Given a table of distances to street vertices from a particular transit stop, create a table of distances to
     * points in this PointSet from the same transit stop.
     * @return A packed array of (pointIndex, distanceMillimeters)
     */
    private int[] extendDistanceTableToPoints(TIntIntMap distanceTableToVertices) {
        int nPoints = this.size();
        TIntIntMap distanceToPoint = new TIntIntHashMap(nPoints, 0.5f, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Edge edge = streetLayer.edgeStore.getCursor();
        // Iterate over all points. This is simpler than iterating over all the reached vertices.
        // Iterating over the reached vertices requires additional indexes and I'm not sure it would be any faster.
        // TODO iterating over all points seems excessive, only a few points will be close to the transit stop.
        for (int p = 0; p < nPoints; p++) {

            // An edge index of -1 for a particular point indicates that this point is unlinked
            if (edges[p] == -1) continue;

            edge.seek(edges[p]);

            int t1 = Integer.MAX_VALUE, t2 = Integer.MAX_VALUE;

            // TODO this is not strictly correct when there are turn restrictions onto the edge this is linked to
            if (distanceTableToVertices.containsKey(edge.getFromVertex()))
                t1 = distanceTableToVertices.get(edge.getFromVertex()) + distances0_mm[p];

            if (distanceTableToVertices.containsKey(edge.getToVertex()))
                t1 = distanceTableToVertices.get(edge.getToVertex()) + distances1_mm[p];

            int t = Math.min(t1, t2);

            if (t != Integer.MAX_VALUE) {
                if (t < distanceToPoint.get(p)) {
                    distanceToPoint.put(p, t);
                }
            }
        }
        if (distanceToPoint.size() == 0) {
            return null;
        }
        // Convert a packed array of pairs.
        // TODO don't put in a list and convert to array, just make an array.
        TIntList packed = new TIntArrayList(distanceToPoint.size() * 2);
        distanceToPoint.forEachEntry((point, distance) -> {
            packed.add(point);
            packed.add(distance);
            return true; // Continue iteration.
        });
        return packed.toArray();
    }

    /**
     * For each transit stop in the associated TransportNetwork, make a table of distances to nearby points in this
     * PointSet.
     *
     * At one point we experimented with doing the entire search from the transit stops all the way up to the points
     * within this method. However, that takes too long when switching PointSets. So we pre-cache distances to all street
     * vertices in the TransitNetwork, and then just extend those tables to the points in the PointSet.
     *
     * This is one of the slowest steps in working with a new scenario. It takes about 50 seconds to link 400000 points.
     * The run time is not shocking when you consider the complexity of the operation: there are nStops * nPoints
     * iterations, which is 8000 * 400000 in the example case. This means 6 msec per transit stop, or 2e-8 sec per
     * point iteration, which is not horribly slow. There are just too many iterations.
     *
     * @param treeRebuildZone only build trees for stops inside this geometry in FIXED POINT DEGREES,
     *                        leaving all the others alone. If null, build trees for all stops.
     */
    public void makeStopToPointDistanceTables(Geometry treeRebuildZone) {
        LOG.info("Creating distance tables from each transit stop to PointSet points.");
        if (treeRebuildZone != null) {
            LOG.info("Selectively computing tables for only those stops that might be affected by the scenario.");
        }
        TransitLayer transitLayer = streetLayer.parentNetwork.transitLayer;
        int nStops = transitLayer.getStopCount();
        LambdaCounter counter = new LambdaCounter(LOG, nStops, 1000,
                "Computed distances to PointSet points from {} of {} transit stops.");
        // Create a distance table from each transit stop to the points in this PointSet in parallel.
        // When applying a scenario, keep the existing distance table for those stops that could not be affected.
        stopToPointDistanceTables = IntStream.range(0, nStops).parallel().mapToObj(stopIndex -> {
            if (treeRebuildZone != null) {
                Point stopPoint = transitLayer.getJTSPointForStopFixed(stopIndex);
                if (stopPoint == null || !treeRebuildZone.contains(stopPoint)) {
                    // This stop is not affected by the scenario. Return the existing distance table, if there is one.
                    // This should only return null for new stops created by the scenario that are unlinked,
                    // because all these new stops should be inside the relink zone.
                    return stopIndex < stopToPointDistanceTables.size() ? stopToPointDistanceTables.get(stopIndex) : null;
                }
            }
            // Get the pre-computed distance table from the stop to the street vertices,
            // which we will extend out from the vertices to the points.
            TIntIntMap distanceTableToVertices = transitLayer.stopToVertexDistanceTables.get(stopIndex);
            counter.increment();
            return distanceTableToVertices == null ? null : extendDistanceTableToPoints(distanceTableToVertices);
        }).collect(Collectors.toList());
        counter.done();
    }
}
