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
import static com.conveyal.r5.streets.StreetRouter.State.RoutingVariable;
import static com.conveyal.r5.transit.TransitLayer.WALK_DISTANCE_LIMIT_METERS;

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

    /* Constants */

    public static final int OFF_STREET_SPEED_MILLIMETERS_PER_SECOND = (int) (1.3f * 1000);

    public static final int BICYCLE_DISTANCE_LINKING_LIMIT_METERS = 5000;

    public static final int CAR_TIME_LINKING_LIMIT_SECONDS = 30 * 60;

    public static final int MAX_CAR_SPEED_METERS_PER_SECOND = 44; // ~160 kilometers per hour

    /* Fields */

    /**
     * LinkedPointSets are long-lived and not extremely numerous, so we keep references to the objects it was built
     * from. Besides these fields are useful for later processing of LinkedPointSets.
     */
    public final PointSet pointSet;

    /**
     * We need to retain the street layer so we can look up edge endpoint vertices for the edge IDs. Besides, this
     * object is inextricably linked to one street network.
     */
    public final StreetLayer streetLayer;

    /**
     * The linkage may be different depending on what mode of travel one uses to reach the points from transit stops.
     * Along with the PointSet and StreetLayer this mode is what determines the contents of a particular
     * LinkedPointSet.
     */
    public final StreetMode streetMode;

    /**
     * Limit to use when building linkageCostTables, re-calculated for different streetModes as needed, using the
     * constants specified above. The value should be larger than any per-leg street mode limits that can be requested
     * in the UI.
     * TODO perhaps we should leave this uninitialized, only initializing once the linkage mode is known around L187.
     * We would then fail fast on any programming errors that don't set or copy the limit.
     */
    int linkingDistanceLimitMeters = WALK_DISTANCE_LIMIT_METERS;

    /**
     * For each point, the closest edge in the street layer. This is in fact the even (forward) edge ID of the closest
     * edge pairs.
     */
    public int[] edges;

    /**
     * For each point, distance from the point to the split point (closest point on the edge to the point to be linked)
     */
    public int[] distancesToEdge_mm;

    /**
     * For each point, distance from the beginning vertex of the edge geometry up to the split point (closest point on
     * the edge to the point to be linked)
     */
    public int[] distances0_mm;

    /**
     * For each point, distance from the end vertex of the edge geometry up to the split point (closest point on the
     * edge to the point to be linked)
     */
    public int[] distances1_mm;

    // TODO Refactor the following three fields out into their own classes

    /** For each transit stop, the distances (or times) to nearby PointSet points as packed (point_index, distance)
     * pairs. */
    public List<int[]> stopToPointLinkageCostTables;

    /**
     * For each pointset point, the stops reachable without using transit, as a map from StopID to distance. For walk
     * and bike, distance is in millimeters; for car, distance is actually time in seconds. Inverted version of
     * stopToPointLinkageCostTables. This is used in PerTargetPropagator to find all the stops near a particular point
     * (grid cell) so we can perform propagation to that grid cell only. We only retain a few percentiles of travel
     * time at each target cell, so doing one cell at a time allows us to keep the output size within reason.
     */
    public transient List<TIntIntMap> pointToStopLinkageCostTables;

    /**
     * By default, linkage costs are distances (between stops and pointset points). For modes where speeds vary
     * by link, it doesn't make sense to store distances, so we store times.
     * TODO perhaps we should leave this uninitialized, only initializing once the linkage mode is known around L510-540.
     * We would then fail fast on any programming errors that don't set or copy the cost unit.
     */
    public RoutingVariable linkageCostUnit = RoutingVariable.DISTANCE_MILLIMETERS;

    /**
     * A LinkedPointSet is a PointSet that has been pre-connected to a StreetLayer in a non-destructive, reversible way.
     * These objects are long-lived and not extremely numerous, so we keep references to the objects it was built from.
     * Besides they are useful for later processing of LinkedPointSets. However once we start evicting
     * TransportNetworks, we have to make sure we're not holding references to entire StreetLayers in LinkedPointSets
     * (memory leak).
     * @param pointSet Points to be linked (e.g. web mercator grid, or eventually centroids)
     * @param streetLayer Streets to which the points should be linked
     * @param streetMode Mode by which to connect with and traverse the street network (e.g. from points to stops)
     * @param baseLinkage Linkage for the base StreetLayer of the supplied streetLayer. If not null, it should have
     *                    the same pointSet and streetMode as the preceding arguments.
     */
    public LinkedPointSet (PointSet pointSet, StreetLayer streetLayer, StreetMode streetMode, LinkedPointSet baseLinkage) {
        LOG.info("Linking pointset to street network...");
        this.pointSet = pointSet;
        this.streetLayer = streetLayer;
        this.streetMode = streetMode;

        final int nPoints = pointSet.featureCount();
        final int nStops = streetLayer.parentNetwork.transitLayer.getStopCount();

        // The regions within which we want to link points to edges, then connect transit stops to points.
        // Null means relink and rebuild everything, but this will be constrained below if a base linkage was supplied.
        Geometry linkageCostRebuildZone = null;

        // TODO general purpose method to check compatability
        // The supplied baseLinkage must be for exactly the same pointSet as the linkage we're creating.
        // This constructor expects them to have the same number of entries for the exact same geographic points.
        // The supplied base linkage should be for the same mode, and for the base street network of this street network.
        if (baseLinkage != null) {
            if (baseLinkage.pointSet != pointSet) {
                throw new AssertionError("baseLinkage must be for the same pointSet as the linkage being created.");
            }
            if (baseLinkage.streetMode != streetMode) {
                throw new AssertionError("baseLinkage must be for the same mode as the linkage being created.");
            }
            if (baseLinkage.streetLayer != streetLayer.baseStreetLayer) {
                throw new AssertionError("baseLinkage must be for the baseStreetLayer of the streetLayer of the linkage being created.");
            }
        }

        if (baseLinkage == null) {
            edges = new int[nPoints];
            distancesToEdge_mm = new int[nPoints];
            distances0_mm = new int[nPoints];
            distances1_mm = new int[nPoints];
            stopToPointLinkageCostTables = new ArrayList<>();
        } else {
            // The caller has supplied an existing linkage for a scenario StreetLayer's base StreetLayer.
            // We want to re-use most of that that existing linkage to reduce linking time.
            LOG.info("Linking a subset of points and copying other linkages from an existing base linkage.");
            LOG.info("The base linkage is for street mode {}", baseLinkage.streetMode);

            // Copy the supplied base linkage into this new LinkedPointSet.
            // The new linkage has the same PointSet as the base linkage, so the linkage arrays remain the same length
            // as in the base linkage. However, if the TransitLayer was also modified by the scenario, the
            // stopToVertexDistanceTables list might need to grow.
            // TODO add assertion that arrays may grow but will never shrink. Check expected array lengths.
            edges = Arrays.copyOf(baseLinkage.edges, nPoints);
            distancesToEdge_mm = Arrays.copyOf(baseLinkage.distancesToEdge_mm, nPoints);
            distances0_mm = Arrays.copyOf(baseLinkage.distances0_mm, nPoints);
            distances1_mm = Arrays.copyOf(baseLinkage.distances1_mm, nPoints);
            stopToPointLinkageCostTables = new ArrayList<>(baseLinkage.stopToPointLinkageCostTables);
            linkageCostUnit = baseLinkage.linkageCostUnit;
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
            if (streetMode == StreetMode.WALK) {
                // limit already set for WALK.
            } else if (streetMode == StreetMode.BICYCLE) {
                linkingDistanceLimitMeters = BICYCLE_DISTANCE_LINKING_LIMIT_METERS;
            } else if (streetMode == StreetMode.CAR) {
                linkingDistanceLimitMeters = CAR_TIME_LINKING_LIMIT_SECONDS * MAX_CAR_SPEED_METERS_PER_SECOND;
            } else {
                throw new UnsupportedOperationException("Unrecognized streetMode");
            }

            linkageCostRebuildZone = streetLayer.scenarioEdgesBoundingGeometry(linkingDistanceLimitMeters);
        }

        // If dealing with a scenario, pad out the stop trees list from the base linkage to match the new stop count.
        // If dealing with a base network linkage, fill the stop trees list entirely with nulls.
        while (stopToPointLinkageCostTables.size() < nStops) stopToPointLinkageCostTables.add(null);

        // First, link the points in this PointSet to specific street vertices.
        // If no base linkage was supplied, parameter will evaluate to true and all points will be linked from scratch.
        this.linkPointsToStreets(baseLinkage == null);

        // Second, make a table of linkage costs (distance or time) from each transit stop to the points in this
        // PointSet.
        this.makeStopToPointLinkageCostTables(linkageCostRebuildZone);

    }

    /**
     * Construct a new LinkedPointSet for a grid that falls entirely within an existing grid LinkedPointSet.
     *
     * @param sourceLinkage a LinkedPointSet whose PointSet must be a WebMercatorGridPointset
     * @param subGrid       the grid for which to create a linkage
     */
    public LinkedPointSet (LinkedPointSet sourceLinkage, WebMercatorGridPointSet subGrid) {

        if (!(sourceLinkage.pointSet instanceof WebMercatorGridPointSet)) {
            throw new IllegalArgumentException("Source linkage must be for a gridded point set.");
        }

        WebMercatorGridPointSet superGrid = (WebMercatorGridPointSet) sourceLinkage.pointSet;
        if (superGrid.zoom != subGrid.zoom) {
            throw new IllegalArgumentException("Source and sub-grid zoom level do not match.");
        }
        if (subGrid.west + subGrid.width < superGrid.west //sub-grid is entirely west of super-grid
                || superGrid.west + superGrid.width < subGrid.west // super-grid is entirely west of sub-grid
                || subGrid.north + subGrid.height < superGrid.north //sub-grid is entirely north of super-grid (note Web Mercator conventions)
                || superGrid.north + superGrid.height < subGrid.north) { //super-grid is entirely north of sub-grid
            LOG.warn("Sub-grid is entirely outside the super-grid.  Points will not be linked to any street edges.");
        }

        // Initialize the fields of the new LinkedPointSet instance.
        // Most characteristics are the same, but the new linkage is for the subset of points in the subGrid.
        pointSet = subGrid;
        streetLayer = sourceLinkage.streetLayer;
        streetMode = sourceLinkage.streetMode;

        int nCells = subGrid.width * subGrid.height;
        edges = new int[nCells];
        distancesToEdge_mm = new int[nCells];
        distances0_mm = new int[nCells];
        distances1_mm = new int[nCells];

        // Copy values over from the source linkage to the new sub-linkage
        // x, y, and pixel are relative to the new linkage
        for (int y = 0, pixel = 0; y < subGrid.height; y++) {
            for (int x = 0; x < subGrid.width; x++, pixel++) {
                int sourceColumn = subGrid.west + x - superGrid.west;
                int sourceRow = subGrid.north + y - superGrid.north;
                if (sourceColumn < 0 || sourceColumn >= superGrid.width || sourceRow < 0 || sourceRow >= superGrid.height) { //point is outside super-grid
                    //Set the edge value to -1 to indicate no linkage.
                    //Distances should never be read downstream, so they don't need to be set here.
                    edges[pixel] = -1;
                } else { //point is inside super-grid
                    int sourcePixel = sourceRow * superGrid.width + sourceColumn;
                    edges[pixel] = sourceLinkage.edges[sourcePixel];
                    distancesToEdge_mm[pixel] = sourceLinkage.distancesToEdge_mm[sourcePixel];
                    distances0_mm[pixel] = sourceLinkage.distances0_mm[sourcePixel];
                    distances1_mm[pixel] = sourceLinkage.distances1_mm[sourcePixel];
                }
            }
        }

        stopToPointLinkageCostTables = sourceLinkage.stopToPointLinkageCostTables.stream()
                .map(distanceTable -> {
                    if (distanceTable == null) return null; // if it was previously unlinked, it is still unlinked

                    TIntList newDistanceTable = new TIntArrayList();
                    for (int i = 0; i < distanceTable.length; i += 2) {
                        int targetInSuperLinkage = distanceTable[i];
                        int distance = distanceTable[i + 1];

                        int superX = targetInSuperLinkage % superGrid.width;
                        int superY = targetInSuperLinkage / superGrid.width;

                        int subX = superX + superGrid.west - subGrid.west;
                        int subY = superY + superGrid.north - subGrid.north;

                        if (subX >= 0 && subX < subGrid.width && subY >= 0 && subY < subGrid.height) {
                            // only retain connections to points that fall within the subGrid
                            int targetInSubLinkage = subY * subGrid.width + subX;
                            newDistanceTable.add(targetInSubLinkage);
                            newDistanceTable.add(distance); // distance to target does not change when we crop the pointset
                        }
                    }

                    if (newDistanceTable.isEmpty()) return null; // not near any points in sub pointset
                    else return newDistanceTable.toArray();
                })
                .collect(Collectors.toList());
    }


    /**
     * Associate the points in this PointSet with the street vertices at the ends of the closest street edge.
     *
     * @param all If true, link all points, otherwise link only those that were previously connected to edges that have
     *            been deleted (i.e. split). We will need to change this behavior when we allow creating new edges
     *            rather than simply splitting existing ones.
     */
    private void linkPointsToStreets (boolean all) {
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
                // Use radius from StreetLayer such that maximum origin and destination walk distances are symmetric.
                Split split = streetLayer.findSplit(pointSet.getLat(p), pointSet.getLon(p),
                        StreetLayer.LINK_RADIUS_METERS, streetMode);
                if (split == null) {
                    edges[p] = -1;
                } else {
                    edges[p] = split.edge;
                    distancesToEdge_mm[p] = split.distanceToEdge_mm;
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
     * A functional interface for fetching the travel time to any street vertex in the transport network. Note that
     * TIntIntMap::get matches this functional interface. There may be a generic IntToIntFunction library interface
     * somewhere, but this interface provides type information about what the function and its parameters mean.
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
        return eval(travelTimeForVertex, 1000, 1000);
    }

    /**
     *
     * @param travelTimeForVertex returning the time required to reach a vertex, in seconds
     * @param onStreetSpeed speed at which the first/last edge is traversed, in millimeters per second. If null, look
     *                     up CAR speed on the edge.
     * @param offStreetSpeed travel speed between the first/last edge and the pointset point, in millimeters per
     *                       second. Generally walking (we don't account for off-street parking not specified in OSM)
     * @return wrapped int[] of travel times (in seconds) to reach the pointset points
     */

    public PointSetTimes eval (TravelTimeFunction travelTimeForVertex, Integer onStreetSpeed, int offStreetSpeed) {
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

            if (time0 == Integer.MAX_VALUE && time1 == Integer.MAX_VALUE) {
                travelTimes[i] = Integer.MAX_VALUE;
                continue;
            }

            // Portion of point-to-vertex time spent on component perpendicular to edge
            int offstreetTime = distancesToEdge_mm[i] / offStreetSpeed;

            // Portion of point-to-vertex time spent along edge, between split point and vertex
            // If a null onStreetSpeed is supplied, look up the speed for cars
            if (onStreetSpeed == null) {
                onStreetSpeed = (int) (edge.getCarSpeedMetersPerSecond() * 1000);
            }

            if (time0 != Integer.MAX_VALUE) {
                time0 += distances0_mm[i] / onStreetSpeed + offstreetTime;
            }
            if (time1 != Integer.MAX_VALUE) {
                time1 += distances1_mm[i] / onStreetSpeed + offstreetTime;
            }

            travelTimes[i] = time0 < time1 ? time0 : time1;
        }
        return new PointSetTimes(pointSet, travelTimes);
    }

    /**
     * Given a table of distances to street vertices from a particular transit stop, create a table of distances to
     * points in this PointSet from the same transit stop. All points outside the distanceTableZone are skipped as an
     * optimization. See JavaDoc on the caller makeStopToPointLinkageCostTables - this is one of the slowest parts of
     * building a network.
     *
     * @return A packed array of (pointIndex, distanceMillimeters), or null if there are no reachable points.
     */
    private int[] extendDistanceTableToPoints (TIntIntMap distanceTableToVertices, Envelope distanceTableZone) {
        int nPoints = this.size();
        TIntIntMap distanceToPoint = new TIntIntHashMap(nPoints, 0.5f, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Edge edge = streetLayer.edgeStore.getCursor();
        // TODO we don't really need a spatial index on a gridded point set.
        // We may not even need a distance table zone: we could just skip all points whose vertices are not in the router result.
        TIntSet relevantPoints = pointSet.spatialIndex.query(distanceTableZone);
        relevantPoints.forEach(p -> {
            // An edge index of -1 for a particular point indicates that this point is unlinked
            if (edges[p] == -1) return true;
            edge.seek(edges[p]);
            int t1 = Integer.MAX_VALUE, t2 = Integer.MAX_VALUE;
            // TODO this is not strictly correct when there are turn restrictions onto the edge this is linked to

            if (distanceTableToVertices.containsKey(edge.getFromVertex())) {
                t1 = distanceTableToVertices.get(edge.getFromVertex()) + distances0_mm[p] + distancesToEdge_mm[p];
            }
            if (distanceTableToVertices.containsKey(edge.getToVertex())) {
                t2 = distanceTableToVertices.get(edge.getToVertex()) + distances1_mm[p] + distancesToEdge_mm[p];
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
     * At one point we experimented with doing the entire search from the transit stops all the way up to the points
     * within this method. However, that takes too long when switching PointSets. So we pre-cache distances to all
     * street vertices in the TransitNetwork, and then just extend those tables to the points in the PointSet.
     * This is one of the slowest steps in working with a new scenario. It takes about 50 seconds to link 400000 points.
     * The run time is not shocking when you consider the complexity of the operation: there are nStops * nPoints
     * iterations, which is 8000 * 400000 in the example case. This means 6 msec per transit stop, or 2e-8 sec per point
     * iteration, which is not horribly slow. There are just too many iterations.
     *
     * @param treeRebuildZone only build trees for stops inside this geometry in FIXED POINT DEGREES, leaving all the
     *                        others alone. If null, build trees for all stops.
     */
    public void makeStopToPointLinkageCostTables(Geometry treeRebuildZone) {
        LOG.info("Creating linkage cost tables from each transit stop to PointSet points.");
        // FIXME this is wasting a lot of memory and not needed for gridded pointsets - overload for gridded and freeform PointSets
        pointSet.createSpatialIndexAsNeeded();
        if (treeRebuildZone != null) {
            LOG.info("Selectively computing tables for only those stops that might be affected by the scenario.");
        }
        TransitLayer transitLayer = streetLayer.parentNetwork.transitLayer;
        int nStops = transitLayer.getStopCount();
        int logFrequency = 1000;
        if (streetMode == StreetMode.CAR) {
            // Log more often because car searches are very slow.
            logFrequency = 100;
        }
        LambdaCounter counter = new LambdaCounter(LOG, nStops, logFrequency,
                "Computed distances to PointSet points from {} of {} transit stops.");
        // Create a distance table from each transit stop to the points in this PointSet in parallel.
        // Each table is a flattened 2D array. Two values for each point reachable from this stop: (pointIndex, cost)
        // When applying a scenario, keep the existing distance table for those stops that could not be affected.
        stopToPointLinkageCostTables = IntStream.range(0, nStops).parallel().mapToObj(stopIndex -> {
            Point stopPoint = transitLayer.getJTSPointForStopFixed(stopIndex);
            // If the stop is not linked to the street network, it should have no distance table.
            if (stopPoint == null) return null;
            if (treeRebuildZone != null && !treeRebuildZone.contains(stopPoint)) {
                // This stop is not affected by the scenario. Return the existing distance table.
                // All new stops created by a scenario should be inside the relink zone, so
                // all stops outside the relink zone should already have a distance table entry.
                if (stopIndex >= stopToPointLinkageCostTables.size()) {
                    throw new AssertionError("A stop created by a scenario is located outside relink zone.");
                }
                return stopToPointLinkageCostTables.get(stopIndex);
            }

            counter.increment();
            Envelope distanceTableZone = stopPoint.getEnvelopeInternal();
            GeometryUtils.expandEnvelopeFixed(distanceTableZone, linkingDistanceLimitMeters);

            if (streetMode == StreetMode.WALK) {
                // Walking distances from stops to street vertices are saved in the transitLayer.
                // Get the pre-computed distance table from the stop to the street vertices,
                // then extend that table out from the street vertices to the points in this PointSet.
                // TODO reuse the code that computes the walk tables at com.conveyal.r5.transit.TransitLayer.buildOneDistanceTable() rather than duplicating it below for other modes.
                TIntIntMap distanceTableToVertices = transitLayer.stopToVertexDistanceTables.get(stopIndex);
                return distanceTableToVertices == null ? null :
                        extendDistanceTableToPoints(distanceTableToVertices, distanceTableZone);
            } else {

                StreetRouter sr = new StreetRouter(transitLayer.parentNetwork.streetLayer);
                sr.streetMode = streetMode;
                int vertexId = transitLayer.streetVertexForStop.get(stopIndex);
                if (vertexId < 0) {
                    LOG.warn("Stop unlinked, cannot build distance table: {}", stopIndex);
                    return null;
                }
                // TODO setting the origin point of the router to the stop vertex does not work.
                // This is probably because link edges do not allow car traversal. We could traverse them.
                // As a stopgap we perform car linking at the geographic coordinate of the stop.
                // sr.setOrigin(vertexId);
                VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(vertexId);
                sr.setOrigin(vertex.getLat(), vertex.getLon());

                if (streetMode == StreetMode.BICYCLE) {

                    sr.distanceLimitMeters = linkingDistanceLimitMeters;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    return extendDistanceTableToPoints(sr.getReachedVertices(), distanceTableZone);

                } else if (streetMode == StreetMode.CAR) {
                    // The speeds for Walk and Bicycle can be specified in an analysis request, so it makes sense above to
                    // store distances and apply the requested speed. In contrast, car speeds vary by link and cannot be
                    // set in analysis requests, so it makes sense to use seconds directly as the linkage cost.
                    // TODO confirm this works as expected when modifications can affect street layer.
                    sr.timeLimitSeconds = CAR_TIME_LINKING_LIMIT_SECONDS;
                    linkageCostUnit = RoutingVariable.DURATION_SECONDS;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    // TODO optimization: We probably shouldn't evaluate at every point in this LinkedPointSet in case it's much bigger than the driving radius.
                    PointSetTimes driveTimesToAllPoints = eval(sr::getTravelTimeToVertex, null,
                            OFF_STREET_SPEED_MILLIMETERS_PER_SECOND);
                    // TODO optimization: should we make spatial index visit() method public to avoid copying results?
                    TIntList packedDriveTimes = new TIntArrayList();
                    for (int p = 0; p < driveTimesToAllPoints.size(); p++) {
                        int driveTimeToPoint = driveTimesToAllPoints.getTravelTimeToPoint(p);
                        if (driveTimeToPoint != Integer.MAX_VALUE) {
                            packedDriveTimes.add(p);
                            packedDriveTimes.add(driveTimeToPoint);
                        }
                    }
                    if (packedDriveTimes.isEmpty()) {
                        return null;
                    } else {
                        return packedDriveTimes.toArray();
                    }
                } else {
                    throw new UnsupportedOperationException("Tried to link a pointset with an unsupported street mode");
                }
            }
        }).collect(Collectors.toList());
        counter.done();
    }

    // FIXME Method and block inside are both synchronized on "this", is that intentional? See comment in internal block.
    public synchronized void makePointToStopDistanceTablesIfNeeded () {
        if (pointToStopLinkageCostTables != null) return;

        synchronized (this) {
            // check again in case they were built while waiting on this synchronized block
            if (pointToStopLinkageCostTables != null) return;
            if (stopToPointLinkageCostTables == null) makeStopToPointLinkageCostTables(null);
            TIntIntMap[] result = new TIntIntMap[size()];

            for (int stop = 0; stop < stopToPointLinkageCostTables.size(); stop++) {
                int[] stopToPointDistanceTable = stopToPointLinkageCostTables.get(stop);
                if (stopToPointDistanceTable == null) continue;

                for (int idx = 0; idx < stopToPointDistanceTable.length; idx += 2) {
                    int point = stopToPointDistanceTable[idx];
                    int distance = stopToPointDistanceTable[idx + 1];
                    if (result[point] == null) result[point] = new TIntIntHashMap();
                    result[point].put(stop, distance);
                }
            }
            pointToStopLinkageCostTables = Arrays.asList(result);
        }
    }


}
