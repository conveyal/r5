package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.scenario.PickupWaitTimes;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.r5.transit.TransitLayer.WALK_DISTANCE_LIMIT_METERS;

/**
 * The final stage of a one-to-many transit trip is what we call "propagation": extending travel times out from all
 * transit stops that were reached to all destination points. This needs to be done hundreds or thousands of times for
 * every stop, or for every target grid cell (depending on the number of simulated schedule Monte Carlo draws, and the
 * number of different departure minutes in the analysis).
 *
 * In order to perform propagation quickly, we pre-build tables of travel distance or time from each transit stop to
 * each destination point. These tables remain identical for a given scenario so are cached in the worker once built.
 * Individual requests may specify lower time limits or car speeds, but these tables place hard upper limits on travel
 * time, distance, and speed. For this reason, egress searches are are more limited than searches for transit access.
 *
 * Typically there are fewer transit stops than destination grid cells, so these tables are built outward from the
 * transit stops. The tables are then transposed for actual use in propagation, where we iterate over the destinations.
 *
 * Note that these cost tables are only needed for egress from public transit. They are not needed for a particular
 * mode if that mode is only being used for access to transit or direct travel to the destination points.
 *
 * EgressCostTables are derived from data in a specific LinkedPointSet, and can be thought of as memoized or cached
 * results from computations on that LinkedPointSet. Therefore there is a one-to-one relationship between
 * LinkedPointSet instances and EgressCostTable instances.
 */
public class EgressCostTable implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(EgressCostTable.class);

    // CONSTANTS

    public static final int BICYCLE_DISTANCE_LINKING_LIMIT_METERS = 5000;

    public static final int CAR_TIME_LINKING_LIMIT_SECONDS = 30 * 60;

    public static final int MAX_CAR_SPEED_METERS_PER_SECOND = 22; // ~80 kilometers per hour

    // FIELDS

    /**
     * The linkage from which these cost tables were built. There is a one-to-one relationship between LinkedPointSet
     * instances and EgressCostTable instances, with the latter being memoized or cached results from computations on
     * one particular LinkedPointSet.
     */
    public final LinkedPointSet linkedPointSet;

    /**
     * By default, linkage costs are distances (between stops and pointset points). For modes where speeds vary
     * by link, it doesn't make sense to store distances, so we store times. This is left uninitialized until the
     * linkage mode is known, so we fail fast on any programming errors that don't set or copy the linkage cost unit.
     */
    public final StreetRouter.State.RoutingVariable linkageCostUnit;

    /**
     * For each transit stop, the distances or times (i.e. "costs") to all nearby PointSet points as a flattened
     * sequence of (point_index, cost) pairs. This is not final to allow it to be nulled when we transpose the table.
     */
    public List<int[]> stopToPointLinkageCostTables;

    /**
     * For each PointSet point, the transit stops from which it can be reached as a map from StopID to distance or time
     * (i.e. "cost"). For walk and bike, distance is in millimeters; for car, distance is actually time in seconds.
     *
     * This is a transposed version of stopToPointLinkageCostTables for direct use in propagation. This is used in
     * PerTargetPropagator to find all the stops near a particular point (grid cell) so we can perform propagation to
     * that grid cell only.
     *
     * We only retain a few percentiles of travel time at each target cell, so handling one cell at a time allows us to
     * keep the output size within reason.
     *
     * TODO This appears to be transient only because the stopToPointLinkageCostTables are more compact (less references).
     * We serialize one walk linkage and associated distance tables along with each TransportNetwork.
     * However, keeping both of these in memory is a huge waste of space. The cost tables are one of the largest and
     * most problematic objects in our application from a memory consumption (and S3 data transfer) point of view.
     */
    private transient List<TIntIntMap> pointToStopLinkageCostTables;

    /**
     * Build an EgressCostTable for the given LinkedPointSet.
     * If the LinkedPointSet is for a scenario built on top of a baseline, elements in the EgressCostTable for the
     * baseline should be reused where possible, with only the differences recomputed.
     *
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
     * It would be possible to pull out pure (even static) functions to set these final fields.
     * Or make some factory methods or classes which produce immutable tables.
     *
     * BaseLinkage may be null if an EgressCostTable is being built for a baseline network.
     */
    public EgressCostTable (LinkedPointSet linkedPointSet, ProgressListener progressListener) {

        final LinkedPointSet baseLinkage = linkedPointSet.baseLinkage; // Can be null, for baseline (non-scenario) linkages.

        final EgressCostTable baseEgressCostTable = (baseLinkage == null) ? null
                : baseLinkage.getEgressCostTable(progressListener);

        this.linkedPointSet = linkedPointSet;

        if (linkedPointSet.streetMode == StreetMode.CAR) {
            this.linkageCostUnit = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        } else {
            this.linkageCostUnit = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        }

        // The regions within which we want to link points to edges, then connect transit stops to points.
        // Null means relink and rebuild everything. This will be constrained below if a base linkage was supplied, to
        // encompass only areas near changed or created edges.
        //
        // Only build trees for stops inside this geometry in FIXED POINT DEGREES, leaving all the others alone.
        // If null, build trees for all stops.
        final Geometry rebuildZone;

        final StreetMode streetMode = linkedPointSet.streetMode;

        /**
         * Limit to use when building linkageCostTables, re-calculated for different streetModes as needed, using the
         * constants specified above. The value should be larger than any per-leg street mode limits that can be requested
         * in the UI.
         * FIXME we should leave this uninitialized, only initializing once the linkage mode is known.
         * We would then fail fast on any programming errors that don't set or copy the limit.
         * FIXME this appears to be used only in the constructor, should we really save it in an instance field? Maybe,
         *       just for debugging.
         */
        final int linkingDistanceLimitMeters;

        if (baseLinkage != null) {
            if (this.linkageCostUnit != baseEgressCostTable.linkageCostUnit) {
                throw new AssertionError("The base linkage's cost table is in the wrong units.");
            }
            // TODO perhaps create alternate constructor of EgressCostTable which copies an existing one
            // TODO We need to determine which points to re-link and which stops should have their stop-to-point tables re-built.
            // TODO check where and how we re-link to streets split/modified by the scenario.
            // This should be all the points within the (bird-fly) linking radius of any modified edge.
            // The stop-to-vertex trees should already be rebuilt elsewhere when applying the scenario.
            // This should be all the stops within the (network or bird-fly) tree radius of any modified edge, including
            // any new stops that were created by the scenario (which will naturally fall within this distance).
            // And build trees from stops to points.
            // Even though currently the only changes to the street network are re-splitting edges to connect new
            // transit stops, we still need to re-link points and rebuild stop trees (both the trees to the vertices
            // and the trees to the points, because some existing stop-to-vertex trees might not include new splitter
            // vertices).
            // FIXME wait why are we only calculating a distance limit when a base linkage is supplied? Because
            //       otherwise we link all points and don't need a radius.
            if (streetMode == StreetMode.WALK) {
                linkingDistanceLimitMeters = WALK_DISTANCE_LIMIT_METERS;
            } else if (streetMode == StreetMode.BICYCLE) {
                linkingDistanceLimitMeters = BICYCLE_DISTANCE_LINKING_LIMIT_METERS;
            } else if (streetMode == StreetMode.CAR) {
                linkingDistanceLimitMeters = CAR_TIME_LINKING_LIMIT_SECONDS * MAX_CAR_SPEED_METERS_PER_SECOND;
            } else {
                throw new UnsupportedOperationException("Unrecognized streetMode");
            }
            rebuildZone = linkedPointSet.streetLayer.scenarioEdgesBoundingGeometry(linkingDistanceLimitMeters);
        } else {
            rebuildZone = null; // rebuild everything.
            // TODO check pre-refactor code, isn't this assuming WALK mode unless there's a base linkage?
            linkingDistanceLimitMeters = WALK_DISTANCE_LIMIT_METERS;
        }

        LOG.info("Creating EgressCostTables from each transit stop to PointSet points.");
        if (rebuildZone != null) {
            LOG.info("Selectively computing tables for only those stops that might be affected by the scenario.");
        }
        TransitLayer transitLayer = linkedPointSet.streetLayer.parentNetwork.transitLayer;
        int nStops = transitLayer.getStopCount();

        // TODO create a multi-counter that can track two different numbers and include them in a single "Done" message.
        // Maybe we should just make a custom ProgressCounter static inner class everywhere one is needed.
        int copyLogFrequency = 5000;
        int computeLogFrequency = 1000;
        if (streetMode == StreetMode.CAR) {
            // Log more often because car searches are very slow.
            computeLogFrequency = 100;
        }

        // TODO only report progress on slow operations (building new tables), not copying existing ones?
        // Precomputing which stops should be rebuilt would allow for nicer progress reporting.
        String taskDescription = String.format("Building %s egress tables for %s",
                linkedPointSet.streetLayer.isScenarioCopy() ? "scenario" : "baseline",
                streetMode.toString().toLowerCase()
        );
        progressListener.beginTask(taskDescription, nStops);

        final LambdaCounter computeCounter = new LambdaCounter(LOG, nStops, computeLogFrequency,
                "Computed new stop -> point tables for {} of {} transit stops.");
        final LambdaCounter copyCounter = new LambdaCounter(LOG, nStops, copyLogFrequency,
                "Copied unchanged stop -> point tables for {} of {} transit stops.");
        // Create a distance table from each transit stop to the points in this PointSet in parallel.
        // Each table is a flattened 2D array. Two values for each point reachable from this stop: (pointIndex, cost)
        // When applying a scenario, keep the existing distance table for those stops that could not be affected.
        // TODO factor out the function that computes a cost table for one stop.
        stopToPointLinkageCostTables = IntStream.range(0, nStops).parallel().mapToObj(stopIndex -> {
            progressListener.increment(); // TODO pre-count points inside rebuild zone, and only show progress for those
            Point stopPoint = transitLayer.getJTSPointForStopFixed(stopIndex);
            // If the stop is not linked to the street network, it should have no distance table.
            if (stopPoint == null) return null;
            if (rebuildZone != null && !rebuildZone.contains(stopPoint)) {
                // This cannot be affected by the scenario. Return the existing distance table.
                // All new stops created by a scenario should be inside the relink zone, so
                // all stops outside the relink zone should already have a distance table entry.
                // TODO having a rebuild zone always implies there is a baseLinkage? If it's possible to not have a base
                //      linkage, this should return null.
                // All stops created by the scenario should by definition be inside the relink zone.
                // This conditional is handling stops outside the relink zone, which should always have existed before
                // scenario application. Therefore they should be present in the base linkage cost tables.
                copyCounter.increment();
                return baseEgressCostTable.stopToPointLinkageCostTables.get(stopIndex);
            }

            computeCounter.increment();
            Envelope envelopeAroundStop = stopPoint.getEnvelopeInternal();
            GeometryUtils.expandEnvelopeFixed(envelopeAroundStop, linkingDistanceLimitMeters);

            if (streetMode == StreetMode.WALK) {
                // Walking distances from stops to street vertices are saved in the TransitLayer.
                // Get the pre-computed walking distance table from the stop to the street vertices,
                // then extend that table out from the street vertices to the points in this PointSet.
                // TODO reuse the code that computes the walk tables at TransitLayer.buildOneDistanceTable() rather than
                //      duplicating it below for other modes.
                TIntIntMap distanceTableToVertices = transitLayer.stopToVertexDistanceTables.get(stopIndex);
                return distanceTableToVertices == null ? null :
                        linkedPointSet.extendDistanceTableToPoints(distanceTableToVertices, envelopeAroundStop);
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
                VertexStore.Vertex vertex = linkedPointSet.streetLayer.vertexStore.getCursor(vertexId);
                sr.setOrigin(vertex.getLat(), vertex.getLon());

                if (streetMode == StreetMode.BICYCLE) {
                    sr.distanceLimitMeters = linkingDistanceLimitMeters;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    return linkedPointSet.extendDistanceTableToPoints(sr.getReachedVertices(), envelopeAroundStop);
                } else if (streetMode == StreetMode.CAR) {
                    // The speeds for Walk and Bicycle can be specified in an analysis request, so it makes sense above to
                    // store distances and apply the requested speed. In contrast, car speeds vary by link and cannot be
                    // set in analysis requests, so it makes sense to use seconds directly as the linkage cost.
                    // TODO confirm this works as expected when modifications can affect street layer.
                    sr.timeLimitSeconds = CAR_TIME_LINKING_LIMIT_SECONDS;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    // TODO optimization: We probably shouldn't evaluate at every point in this LinkedPointSet in case
                    //      it's much bigger than the driving radius.
                    PointSetTimes driveTimesToAllPoints = linkedPointSet.eval(
                            sr::getTravelTimeToVertex,
                            null,
                            LinkedPointSet.OFF_STREET_SPEED_MILLIMETERS_PER_SECOND,
                            null
                    );
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
        computeCounter.done();
        copyCounter.done();

        // Now that we have a full set of tables, filter and transform them to reflect on-demand egress service.
        // TODO optimization: we could return null immediately above for all stops not served by the on-demand egress:
        // if (onDemandEgressService != null && ! onDemandEgressService.allows(stopIndex)) return null;
        // We could also integrate the geographic filtering into that same loop and extendDistanceTableToPoints.
        // In fact this is a major optimization since building car tables is very slow, and we might only need a few.
        StreetLayer streetLayer = transitLayer.parentNetwork.streetLayer;
        PickupWaitTimes pickupWaitTimes = streetLayer.pickupWaitTimes;
        if (pickupWaitTimes != null && pickupWaitTimes.streetMode == streetMode) {
            if (streetMode != StreetMode.CAR) {
                // FIXME only cars have egress cost tables in seconds. Others will need a constant time offset field.
                throw new RuntimeException("Only car egress tables can have a baked in time delay.");
            }
            for (int s = 0; s < stopToPointLinkageCostTables.size(); s++) {
                PickupWaitTimes.EgressService egressService = pickupWaitTimes.getEgressService(s);
                if (egressService == null || egressService.waitTimeSeconds < 0) {
                    stopToPointLinkageCostTables.set(s, null);
                    continue;
                }
                int[] costs = stopToPointLinkageCostTables.get(s);
                TIntList filteredCosts = new TIntArrayList();
                for (int i = 0; i < costs.length; i += 2) {
                    int point = costs[i];
                    int cost = costs[i + 1];
                    // TODO normalize variable names (to costs?), these are not just times they may be distances.
                    // TODO linkedPointSet.pointSet.getPointsInGeometry(), and pointInsideGeometry? default defs.
                    double lat = linkedPointSet.pointSet.getLat(point);
                    double lon = linkedPointSet.pointSet.getLon(point);
                    if (GeometryUtils.containsPoint(egressService.serviceArea, lon, lat)) {
                        filteredCosts.add(point);
                        filteredCosts.add(cost + egressService.waitTimeSeconds);
                    }
                }
                // null represents an empty array, which we presume may be more efficiently serializable than lots of
                // references to a single empty array instance.
                int[] filteredCostsArray = filteredCosts.isEmpty() ? null : filteredCosts.toArray();
                stopToPointLinkageCostTables.set(s, filteredCostsArray);
            }
        }
    }

    /**
     * Private constructor used by factory methods or other constructors to allow fields to be immutable.
     */
    private EgressCostTable (LinkedPointSet linkedPointSet,
                            StreetRouter.State.RoutingVariable linkageCostUnit,
                            List<int[]> stopToPointLinkageCostTables) {
        this.linkedPointSet = linkedPointSet;
        this.linkageCostUnit = linkageCostUnit;
        this.stopToPointLinkageCostTables = stopToPointLinkageCostTables;
    }

    /**
     * Factory method for copying a strict sub-geographic area, with no rebuilding of any linkages or tables.
     * If implemented as a constructor, this has a similar or identical signature to the other constructor, which makes
     * me wonder if they can or should be combined into a single constructor that can perform cropping and scenario
     * (re)linking and cost table building.
     *
     * By taking an EgressCostTable instead of a LinkedPointSet as a parameter, we guarantee that both the source
     * LinkedPointSet and EgressCostTable are defined.
     */
    public static EgressCostTable geographicallyCroppedCopy (LinkedPointSet subLinkage, ProgressListener progressListener) {

        LinkedPointSet superLinkage = subLinkage.baseLinkage;
        EgressCostTable superCostTable = superLinkage.getEgressCostTable(progressListener);

        final WebMercatorGridPointSet superGrid = (WebMercatorGridPointSet) superLinkage.pointSet;
        final WebMercatorGridPointSet subGrid = (WebMercatorGridPointSet) subLinkage.pointSet;

        // For each transit stop, we have a table of costs to reach pointset points (or null if none can be reached).
        // If such tables have already been built for the source linkage, copy them and crop to a smaller rectangle as
        // needed (as was done for the basic linkage information above).
        List<int[]> stopToPointLinkageCostTables = superCostTable.stopToPointLinkageCostTables.stream()
                .map(distanceTable -> {
                    if (distanceTable == null) {
                        // If the stop could not reach any points in the super-pointset,
                        // it cannot reach any points in this sub-pointset.
                        return null;
                    }
                    TIntList newDistanceTable = new TIntArrayList();
                    for (int i = 0; i < distanceTable.length; i += 2) {
                        int targetInSuperLinkage = distanceTable[i];
                        int distance = distanceTable[i + 1];

                        int superX = targetInSuperLinkage % superGrid.width;
                        int superY = targetInSuperLinkage / superGrid.width;

                        int subX = superX + superGrid.west - subGrid.west;
                        int subY = superY + superGrid.north - subGrid.north;

                        // Only retain distance information for points that fall within this sub-grid.
                        if (subX >= 0 && subX < subGrid.width && subY >= 0 && subY < subGrid.height) {
                            int targetInSubLinkage = subY * subGrid.width + subX;
                            newDistanceTable.add(targetInSubLinkage);
                            newDistanceTable.add(distance); // distance to target does not change when we crop the pointset
                        }
                    }
                    if (newDistanceTable.isEmpty()) {
                        // No points in the sub-pointset can be reached from this transit stop.
                        return null;
                    }
                    return newDistanceTable.toArray();
                })
                .collect(Collectors.toList());

        return new EgressCostTable(subLinkage, superCostTable.linkageCostUnit, stopToPointLinkageCostTables);
    }

    /**
     * This method transposes the cost tables, yielding impedance from each point back to all stops that can reach it.
     * The original calculation is performed from each stop out to the points it can reach, primarily because there are
     * usually fewer stops than destination points.
     *
     * Throwing away the original stop -> point tables should save a lot of memory. We null out the entries in the
     * source list as they are converted, allowing garbage collection of values.
     *
     * The geographic cropping and scenario base copying processes expect the original stop -> point tables to still
     * exist. However, each table seems to be used only as a source table for copies, or as a propagation table, but not
     * both. Copied tables are always used for propagation, and once any one thread uses it for propagation it should
     * never be a source for any other copies.
     *
     * We were effectively already avoiding data duplication in the region-wide cost tables by never calling the method
     * that lazily transposed the tables.
     * TODO really we should have separate EgressCostTable and PropagationEgressCostTable classes, one copied from the other.
     * One should represent the region, or read-through crops of the whole region, and the other should be per-scenario.
     */
    public synchronized void destructivelyTransposeForPropagationAsNeeded() {
        if (pointToStopLinkageCostTables == null) {
            // Release reference to the source table, in order to fail fast if any other thread tries to read them.
            // We make a local copy so we can release each reference while copying.
            List<int[]> stopToPointTables = new ArrayList<>(this.stopToPointLinkageCostTables);
            this.stopToPointLinkageCostTables = null;
            TIntIntMap[] result = new TIntIntMap[linkedPointSet.size()];
            for (int stop = 0; stop < stopToPointTables.size(); stop++) {
                int[] stopToPointTable = stopToPointTables.get(stop);
                if (stopToPointTable == null) {
                    continue;
                }
                for (int idx = 0; idx < stopToPointTable.length; idx += 2) {
                    int point = stopToPointTable[idx];
                    int distance = stopToPointTable[idx + 1];
                    if (result[point] == null) {
                        result[point] = new TIntIntHashMap();
                    }
                    result[point].put(stop, distance);
                }
                // Release the reference to this stop's table for garbage collection.
                stopToPointTables.set(stop, null);
            }
            // Make the transposed table available to propagation.
            this.pointToStopLinkageCostTables = Arrays.asList(result);
        }
    }

    /**
     * You should first call destructivelyTransposeForPropagationAsNeeded before calling this method.
     * @return for the given destination point index, a map from stop_index -> cost_to_reach_point for all nearby stops
     */
    public TIntIntMap getCostTableForPoint (int pointIndex) {
        return pointToStopLinkageCostTables.get(pointIndex);
    }

}
