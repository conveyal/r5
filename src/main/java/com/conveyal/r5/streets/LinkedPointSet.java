package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.util.LambdaCounter;
import com.vividsolutions.jts.geom.*;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import com.conveyal.r5.streets.EdgeStore.Edge;
import gnu.trove.set.TIntSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
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
public class LinkedPointSet implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(LinkedPointSet.class);

    /**
     * The distance we search around each PointSet point for a road to link it to.
     * FIXME 1KM is really far to walk off a street. But some places have offices in the middle of big parking lots.
     */
    public static final int MAX_OFFSTREET_WALK_METERS = 4000;

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
    public List<int[]> stopToPointDistanceTables;

    /**
     * For each pointset point, the stops reachable without using transit, as a map from StopID to distance in millimeters.
     * Inverted version of stopToPointDistanceTables.
     */
    public transient List<TIntIntMap> pointToStopDistanceTables;

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
        Geometry treeRebuildZone = null;

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

            LOG.info("Linking a subset of points and copying other linkages from base layer");

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
            treeRebuildZone = streetLayer.scenarioEdgesBoundingGeometry(TransitLayer.DISTANCE_TABLE_SIZE_METERS);
        }

        // If dealing with a scenario, pad out the stop trees list from the base linkage to match the new stop count.
        // If dealing with a base network linkage, fill the stop trees list entirely with nulls.
        while (stopToPointDistanceTables.size() < nStops) stopToPointDistanceTables.add(null);

        /* First, link the points in this PointSet to specific street vertices. If there is no base linkage, link all streets. */
        if (streetLayer.hasOSM) this.linkPointsToStreets(baseLinkage == null);

        /* Second, make a table of distances from each transit stop to the points in this PointSet. */
        this.makeStopToPointDistanceTables(treeRebuildZone);

    }


    /**
     * Construct a new LinkedPointSet for a grid that falls entirely within an existing grid LinkedPointSet.
     * @param sourceLinkage a LinkedPointSet whose PointSet must be a WebMercatorGridPointset
     * @param subGrid the grid for which to create a linkage
     */
    public LinkedPointSet(LinkedPointSet sourceLinkage, WebMercatorGridPointSet subGrid) {

        if (!(sourceLinkage.pointSet instanceof WebMercatorGridPointSet)) {
            throw new IllegalArgumentException("Source linkage must be for a gridded point set.");
        }

        WebMercatorGridPointSet superGrid = (WebMercatorGridPointSet) sourceLinkage.pointSet;
        if (superGrid.zoom != subGrid.zoom) {
            throw new IllegalArgumentException("Source and sub-grid zoom level do not match.");
        }
        if (superGrid.west > subGrid.west || superGrid.west + superGrid.width < subGrid.west + subGrid.width ||
            superGrid.north > subGrid.north || superGrid.north + superGrid.height < subGrid.north + subGrid.height) {
            throw new IllegalArgumentException("Sub-grid must lie fully inside the super-grid.");
        }

        // Initialize the fields of the new LinkedPointSet instance
        pointSet = sourceLinkage.pointSet;
        streetLayer = sourceLinkage.streetLayer;
        streetMode = sourceLinkage.streetMode;

        int nCells = subGrid.width * subGrid.height;
        edges = new int[nCells];
        distances0_mm = new int[nCells];
        distances1_mm = new int[nCells];

        // Copy values over from the source linkage to the new sub-linkage
        // x, y, and pixel are relative to the new linkage
        for (int y = 0, pixel = 0; y < subGrid.height; y++) {
            for (int x = 0; x < subGrid.width; x++, pixel++) {
                int sourceColumn = subGrid.west + x - superGrid.west;
                int sourceRow = subGrid.north + y - superGrid.north;
                int sourcePixel = sourceRow * superGrid.width + sourceColumn;
                edges[pixel] = sourceLinkage.edges[sourcePixel];
                distances0_mm[pixel] = sourceLinkage.distances0_mm[sourcePixel];
                distances1_mm[pixel] = sourceLinkage.distances1_mm[sourcePixel];
            }
        }

    }


    /**
     * Associate the points in this PointSet with the street vertices at the ends of the closest street edge.
     * @param all If true, link all points, otherwise link only those that were previously connected to edges that have
     *            been deleted (i.e. split).
     *            We will need to change this behavior when we allow creating new edges rather than simply splitting
     *            existing ones.
     */
    private void linkPointsToStreets(boolean all) {
        LambdaCounter counter = new LambdaCounter(LOG, pointSet.featureCount(), 10000,
                "Linked {} of {} PointSet points to streets.");
        // Perform linkage calculations in parallel, writing results to the shared parallel arrays.
        IntStream.range(0, pointSet.featureCount()).parallel().forEach(p -> {
            // When working with a scenario, skip all points that are not linked to a deleted street (i.e. one that has
            // been split). At the current time, the only street network modification we support is splitting existing streets,
            // so the only way a point can need to be relinked is if it is connected to a street which was split (and therefore deleted).
            // FIXME when we permit street network modifications beyond adding transit stops we will need to change how this works,
            // we may be able to use some type of flood-fill algorithm in geographic space, expanding the relink envelope until we
            // hit edges on all sides or reach some predefined maximum.
            if (all || (streetLayer.edgeStore.temporarilyDeletedEdges != null &&
                        streetLayer.edgeStore.temporarilyDeletedEdges.contains(edges[p]))) {
                Split split = streetLayer.findSplit(pointSet.getLat(p), pointSet.getLon(p), MAX_OFFSTREET_WALK_METERS, streetMode);
                if (split == null) {
                    edges[p] = -1;
                } else {
                    edges[p] = split.edge;
                    distances0_mm[p] = split.distance0_mm;
                    distances1_mm[p] = split.distance1_mm;
                }
                counter.increment();
            }
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


    @Deprecated
    public PointSetTimes eval (TravelTimeFunction travelTimeForVertex) {
        // R5 used to not differentiate between seconds and meters, preserve that behavior in this deprecated function
        // by using 1 m / s
        return eval(travelTimeForVertex, 1000);
    }

    public PointSetTimes eval (TravelTimeFunction travelTimeForVertex, int offstreetTravelSpeedMillimetersPerSecond) {
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
                time0 += distances0_mm[i] / offstreetTravelSpeedMillimetersPerSecond;
            }
            if (time1 != Integer.MAX_VALUE) {
                time1 += distances1_mm[i] / offstreetTravelSpeedMillimetersPerSecond;
            }
            travelTimes[i] = time0 < time1 ? time0 : time1;
        }
        return new PointSetTimes (pointSet, travelTimes);
    }

    /**
     * Given a table of distances to street vertices from a particular transit stop, create a table of distances to
     * points in this PointSet from the same transit stop.
     * All points outside the distanceTableZone are skipped as an optimization.
     * See JavaDoc on the caller makeStopToPointDistanceTables - this is one of the slowest parts of building a network.
     * @return A packed array of (pointIndex, distanceMillimeters)
     */
    private int[] extendDistanceTableToPoints(TIntIntMap distanceTableToVertices, Envelope distanceTableZone) {
        int nPoints = this.size();
        TIntIntMap distanceToPoint = new TIntIntHashMap(nPoints, 0.5f, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Edge edge = streetLayer.edgeStore.getCursor();
        TIntSet relevantPoints = pointSet.spatialIndex.query(distanceTableZone);
        relevantPoints.forEach(p -> {
            // An edge index of -1 for a particular point indicates that this point is unlinked
            if (edges[p] == -1) return true;
            edge.seek(edges[p]);
            int t1 = Integer.MAX_VALUE, t2 = Integer.MAX_VALUE;
            // TODO this is not strictly correct when there are turn restrictions onto the edge this is linked to
            if (distanceTableToVertices.containsKey(edge.getFromVertex())) {
                t1 = distanceTableToVertices.get(edge.getFromVertex()) + distances0_mm[p];
            }
            if (distanceTableToVertices.containsKey(edge.getToVertex())) {
                t2 = distanceTableToVertices.get(edge.getToVertex()) + distances1_mm[p];
            }
            int t = Math.min(t1, t2);
            if (t != Integer.MAX_VALUE) {
                if (t < distanceToPoint.get(p)) {
                    distanceToPoint.put(p, t);
                }
            }
            return true; // Continue iteration.
        });
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
        pointSet.createSpatialIndexAsNeeded();
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
            Point stopPoint = transitLayer.getJTSPointForStopFixed(stopIndex);
            // If the stop is not linked to the street network, it should have no distance table.
            if (stopPoint == null) return null;
            if (treeRebuildZone != null && !treeRebuildZone.contains(stopPoint)) {
                // This stop is not affected by the scenario. Return the existing distance table.
                // All new stops created by a scenario should be inside the relink zone, so
                // all stops outside the relink zone should already have a distance table entry.
                if (stopIndex >= stopToPointDistanceTables.size()) {
                    throw new AssertionError("A stop created by a scenario is located outside relink zone.");
                }
                return stopToPointDistanceTables.get(stopIndex);
            }
            // Get the pre-computed distance table from the stop to the street vertices,
            // then extend that table out from the street vertices to the points in this PointSet.
            TIntIntMap distanceTableToVertices = transitLayer.stopToVertexDistanceTables.get(stopIndex);
            Envelope distanceTableZone = stopPoint.getEnvelopeInternal();
            GeometryUtils.expandEnvelopeFixed(distanceTableZone, TransitLayer.DISTANCE_TABLE_SIZE_METERS);
            int[] distancesToPoints = distanceTableToVertices == null ? null :
                    extendDistanceTableToPoints(distanceTableToVertices, distanceTableZone);
            counter.increment();
            return distancesToPoints;
        }).collect(Collectors.toList());
        counter.done();
    }

    public synchronized void makePointToStopDistanceTablesIfNeeded () {
        if (pointToStopDistanceTables != null) return;

        synchronized (this) {
            // check again in case they were built while waiting on this synchronized block
            if (pointToStopDistanceTables != null) return;
            if (stopToPointDistanceTables == null) makeStopToPointDistanceTables(null);
            TIntIntMap[] result = new TIntIntMap[size()];

            for (int stop = 0; stop < stopToPointDistanceTables.size(); stop++) {
                int[] stopToPointDistanceTable = stopToPointDistanceTables.get(stop);
                if (stopToPointDistanceTable == null) continue;

                for (int idx = 0; idx < stopToPointDistanceTable.length; idx += 2) {
                    int point = stopToPointDistanceTable[idx];
                    int distance = stopToPointDistanceTable[idx + 1];
                    if (result[point] == null) result[point] = new TIntIntHashMap();
                    result[point].put(stop, distance);
                }
            }
            pointToStopDistanceTables = Arrays.asList(result);
        }
    }


}
