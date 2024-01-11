package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.progress.NoopProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore.Edge;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.IntStream;

import static com.conveyal.r5.streets.StreetRouter.State.RoutingVariable;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

/**
 * A LinkedPointSet is a PointSet that has been connected to a StreetLayer in a non-destructive, reversible way.
 * For each feature in the PointSet, we record the closest edge useable by the specified StreetMode, and the distance
 * to the vertices at the ends of that edge. There should be a mapping (PointSet, StreetLayer, StreetMode) ==>
 * LinkedPointSet.
 *
 * LinkedPointSet is serializable because we save one PointSet and the associated WALK linkage in each Network to speed
 * up the time to first response on this common mode. We might want to also store linkages for other common modes.
 *
 * FIXME a LinkedPointSet is not a PointSet, it's associated with a PointSet. It should be called PointSetLinkage.
 */
public class LinkedPointSet implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(LinkedPointSet.class);

    // CONSTANTS

    public static final int OFF_STREET_SPEED_MILLIMETERS_PER_SECOND = (int) (1.3f * 1000);

    // FIELDS IDENTIFYING THIS OBJECT
    // A LinkedPointSet is uniquely derived from: a PointSet, linked to a particular StreetLayer, for a given StreetMode.

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

    // FIELDS CONTAINING LINKAGE INFORMATION
    // For each point, we store the distance to each end of the nearest edge.

    /**
     * For each point, the closest edge in the street layer. This is in fact the even (forward) edge ID of the closest
     * edge pairs.
     */
    public final int[] edges;

    /**
     * For each point, distance from the point to the split point (closest point on the edge to the point to be linked)
     */
    public final int[] distancesToEdge_mm;

    /**
     * For each point, distance from the beginning vertex of the edge geometry up to the split point (closest point on
     * the edge to the point to be linked)
     */
    public final int[] distances0_mm;

    /**
     * For each point, distance from the end vertex of the edge geometry up to the split point (closest point on the
     * edge to the point to be linked)
     */
    public final int[] distances1_mm;

    /**
     * LinkedPointSets and their EgressCostTables are often copied from existing ones.
     * This field holds a reference to the source linkage from which the copy was made.
     * But there are two different ways linkages and cost tables can be based on existing ones:
     * Sometimes we perform a simple crop to a smaller geographic area; other times we recompute linkages and
     * tables for a scenario that was built on top of a baseline network. By current design, we never do both at
     * once. They would be done as two successive operations yielding new objects each time. TODO verify that fact.
     */
    public final LinkedPointSet baseLinkage;

    /**
     * As mentioned above, sometimes the new pointset is cropped out of the base linkage, other times built upon it to
     * reflect scenario modifications. If this field is true, it was produced by cropping with no scenario application.
     * This field is somewhat of a hack, part of the way we've deferred building the voluminous distance tables.
     * As a simple first refactor, I'm keeping the existing two functions separate and just deferring the call until we
     * are sure the distance table is needed. So in a way this is just a "function pointer" stating which distance table
     * builder function to call.
     */
    private final boolean cropped;

    /**
     * The egress cost tables used to be included directly in LinkedPointSets, but are now factored out into a class.
     * This field is not final because it's only built when needed.
     */
    private EgressCostTable egressCostTable;

    /**
     * A LinkedPointSet is a PointSet that has been pre-connected to a StreetLayer in a non-destructive, reversible way.
     * These objects are long-lived and not extremely numerous, so we keep references to the objects it was built from.
     * Besides they are useful for later processing of LinkedPointSets. However once we start evicting
     * TransportNetworks, we have to make sure we're not holding references to entire StreetLayers in
     * LinkedPointSets. FIXME: memory leak?
     * When building a linkage for a scenario, the linkage for the baseline on which the scenario is built can be
     * supplied as an optimization. We will reuse linkages and cost tables from that baseline as much as possible.
     *
     * @param pointSet Points to be linked (e.g. web mercator grid, or eventually centroids)
     * @param streetLayer Streets to which the points should be linked
     * @param streetMode Mode by which to connect with and traverse the street network (e.g. from points to stops)
     * @param baseLinkage Linkage for the base StreetLayer of the supplied streetLayer. If not null, it should have
     *                    the same pointSet and streetMode as the preceding arguments.
     */
    public LinkedPointSet (PointSet pointSet, StreetLayer streetLayer, StreetMode streetMode, LinkedPointSet baseLinkage) {
        LOG.info("Linking pointset to street network for mode {}...", streetMode);
        this.pointSet = pointSet;
        this.streetLayer = streetLayer;
        this.streetMode = streetMode;
        this.baseLinkage = baseLinkage;
        this.cropped = false; // This allows calling the correct cost table builder function later, see Javadoc on field.

        final int nPoints = pointSet.featureCount();

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

        }

        // First, link the points in this PointSet to specific street vertices.
        // If no base linkage was supplied, parameter will evaluate to true and all points will be linked from scratch.
        this.linkPointsToStreets(baseLinkage == null);
    }

    /**
     * Construct a new LinkedPointSet for a grid that falls entirely within an existing gridded LinkedPointSet.
     * FIXME in fact this does not require the subgrid to fall entirely within the existing grid.
     *       Change Javadoc to reflect actual behaviour.
     *
     * @param sourceLinkage a LinkedPointSet whose PointSet must be a WebMercatorGridPointset
     * @param subGrid       the grid for which to create a linkage
     */
    public LinkedPointSet (LinkedPointSet sourceLinkage, WebMercatorGridPointSet subGrid) {
        if (!(sourceLinkage.pointSet instanceof WebMercatorGridPointSet)) {
            throw new IllegalArgumentException("Source linkage must be for a gridded point set.");
        }
        WebMercatorGridPointSet superGrid = (WebMercatorGridPointSet) sourceLinkage.pointSet;
        if (superGrid.extents.zoom != subGrid.extents.zoom) {
            throw new IllegalArgumentException("Source and sub-grid zoom level do not match.");
        }
        if (subGrid.extents.west + subGrid.extents.width < superGrid.extents.west //sub-grid is entirely west of super-grid
                || superGrid.extents.west + superGrid.extents.width < subGrid.extents.west // super-grid is entirely west of sub-grid
                || subGrid.extents.north + subGrid.extents.height < superGrid.extents.north //sub-grid is entirely north of super-grid (note Web Mercator conventions)
                || superGrid.extents.north + superGrid.extents.height < subGrid.extents.north) { //super-grid is entirely north of sub-grid
            LOG.warn("Sub-grid is entirely outside the super-grid.  Points will not be linked to any street edges.");
        }

        // Initialize the fields of the new LinkedPointSet instance.
        // Most characteristics are the same, but the new linkage is for the subset of points in the subGrid.
        this.pointSet = subGrid;
        this.streetLayer = sourceLinkage.streetLayer;
        this.streetMode = sourceLinkage.streetMode;
        this.baseLinkage = sourceLinkage;
        this.cropped = true; // This allows calling the correct cost table builder function later, see Javadoc on field.

        int nCells = subGrid.extents.width * subGrid.extents.height;
        edges = new int[nCells];
        distancesToEdge_mm = new int[nCells];
        distances0_mm = new int[nCells];
        distances1_mm = new int[nCells];

        // FIXME Grid-cropping math here and in EgressCostTable secondary constructor is identical.
        //       This seems to imply we should have a subgrid-mapping class.

        // Copy a subset of linkage information (edges and distances for each cell) over from the source linkage to
        // the new sub-linkage. This basically crops a smaller rectangle out of the larger one (or copies it if
        // dimensions are the same). Variables x, y, and pixel are relative to the new linkage, not the source one.
        for (int y = 0, pixel = 0; y < subGrid.extents.height; y++) {
            for (int x = 0; x < subGrid.extents.width; x++, pixel++) {
                int sourceColumn = subGrid.extents.west + x - superGrid.extents.west;
                int sourceRow = subGrid.extents.north + y - superGrid.extents.north;
                if (sourceColumn < 0 || sourceColumn >= superGrid.extents.width || sourceRow < 0 || sourceRow >= superGrid.extents.height) { //point is outside super-grid
                    // Set the edge value to -1 to indicate no linkage.
                    // Distances should never be read downstream, so they don't need to be set here.
                    edges[pixel] = -1;
                } else { //point is inside super-grid
                    int sourcePixel = sourceRow * superGrid.extents.width + sourceColumn;
                    edges[pixel] = sourceLinkage.edges[sourcePixel];
                    distancesToEdge_mm[pixel] = sourceLinkage.distancesToEdge_mm[sourcePixel];
                    distances0_mm[pixel] = sourceLinkage.distances0_mm[sourcePixel];
                    distances1_mm[pixel] = sourceLinkage.distances1_mm[sourcePixel];
                }
            }
        }
    }

    /**
     * Get (and lazily build) the EgressCostTable derived from this linkage and its associated TransportNetwork.
     * The synchronization is rather crude, but should do the job as long as all outside multi-threaded access to the
     * cost table is via this method. Note that this will recursively lock the chain of base linkages on which this
     * linkage is built, but if this is the only synchronized method then the linkage is still usable by non-egress
     * searches simultaneously. This is being pretty heavily called though, maybe locking should only happen once we
     * see that the table is null.
     *
     * Rather than making these a property of LinkedPointSets, we may want to define them as a standalone entity that
     * has its own factory class and loader/cache class keyed on LinkedPointSets.
     *
     * Note below that when we crop or scenario-rebuild an egress cost table, the required fields are always one
     * LinkedPointSet, and the egressCostTable from the baseLinkage (if any) of that linkedPointSet. The baseLinkage
     * is now available as a field on the current linkage, so it does not need to be passed as a parameter. So a
     * factory class could depend on only a progressListener and the current LinkedPointSet.
     */
    public synchronized EgressCostTable getEgressCostTable (ProgressListener progressListener) {
        if (this.egressCostTable == null) {
            if (this.cropped) {
                // This LinkedPointSet was simply cropped out of a larger existing one.
                this.egressCostTable = EgressCostTable.geographicallyCroppedCopy(this, progressListener);
            } else {
                // This is a rebuild for a diff between a scenario and a baseline.
                this.egressCostTable = new EgressCostTable(this, progressListener);
            }
        }
        return this.egressCostTable;
    }

    /**
     * Fetch the egressCostTable when you expect it to be already built.
     * Eventually we should eliminate all calls to this method.
     * We could also pass in a ProgressListener that logs instead of updating the web API.
     *
     * Really we should never be triggering a lazy table build anywhere a progress listener is not supplied.
     * We should request the table from a cache up front, in the NetworkPreloader, then it should be retained in a
     * single "holder" object along with the network, linkages etc. and those pre-loaded references should be used
     * throughout the rest of the task's processing.
     */
    public synchronized EgressCostTable getEgressCostTable () {
        return getEgressCostTable(new NoopProgressListener());
    }

    /**
     * Associate the points in this PointSet with the street vertices at the ends of the closest street edge.
     *
     * @param all If true, link all points, otherwise link only those that were previously connected to edges that have
     *            been deleted (i.e. split), or that are in proximity to an added edge.
     */
    private void linkPointsToStreets (boolean all) {
        LambdaCounter linkCounter = new LambdaCounter(LOG, pointSet.featureCount(), 10000,
                String.format("Linked {} of {} PointSet points to streets for mode %s.", streetMode));

        // Construct a geometry around any edges added by the scenario, or null if there are no added edges.
        // As it is derived from edge geometries this is a fixed-point geometry and must be intersected with the same.
        final Geometry addedEdgesBoundingGeometry = streetLayer.addedEdgesBoundingGeometry();

        // Perform linkage calculations in parallel, writing results to the shared parallel arrays.
        IntStream.range(0, pointSet.featureCount()).parallel().forEach(p -> {
            // When working with a scenario, skip all points that are not linked to a deleted street (i.e. one that has
            // been split). At the current time, the only street network modification we support is splitting existing streets,
            // so the only way a point can need to be relinked is if it is connected to a street which was split (and therefore deleted).
            // FIXME when we permit street network modifications beyond adding transit stops we will need to change how this works,
            // we may be able to use some type of flood-fill algorithm in geographic space, expanding the relink envelope until we
            // hit edges on all sides or reach some predefined maximum.
            boolean relinkThisPoint = false;
            if (all || streetLayer.edgeIsDeletedByScenario(edges[p])) {
                relinkThisPoint = true;
            } else if (addedEdgesBoundingGeometry != null) {
                // If we have a geometry for added edges, see whether those might come closer than any existing linkage.
                double pointLatFixed = floatingDegreesToFixed(pointSet.getLat(p));
                double pointLonFixed = floatingDegreesToFixed(pointSet.getLon(p));
                Envelope pointEnvelopeFixed = new Envelope(pointLonFixed, pointLonFixed, pointLatFixed, pointLatFixed);
                double radiusMeters = StreetLayer.LINK_RADIUS_METERS;
                if (edges[p] != -1) {
                    radiusMeters = this.distancesToEdge_mm[p] / 1000.0;
                }
                GeometryUtils.expandEnvelopeFixed(pointEnvelopeFixed, radiusMeters);
                if (addedEdgesBoundingGeometry.intersects(GeometryUtils.geometryFactory.toGeometry(pointEnvelopeFixed))) {
                    relinkThisPoint = true;
                }
            }
            if (relinkThisPoint) {
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
                linkCounter.increment();
            }
        });
        linkCounter.done();
        {
            int totalPoints = pointSet.featureCount();
            int refreshedPoints = linkCounter.getCount();
            int copiedPoints = totalPoints - refreshedPoints;
            int changedPoints = 0;
            int changedToBaselineEdge = 0;
            int changedToAddedEdge = 0;
            int changedToUnlinked = 0;
            if (baseLinkage != null) {
                for (int p = 0; p < totalPoints; p++) {
                    if (baseLinkage.edges[p] != this.edges[p]) {
                        changedPoints += 1;
                        if (this.edges[p] < 0) {
                            changedToUnlinked += 1;
                        } else if (streetLayer.edgeIsAddedByScenario(this.edges[p])) {
                            changedToAddedEdge += 1;
                        } else {
                            changedToBaselineEdge += 1;
                        }
                    }
                }
            }
            LOG.info("      {} of {} point linkages were copied directly from a source linkage;",
                    copiedPoints, totalPoints);
            LOG.info("      the remaining {} linkages were refreshed, of which {} changed;",
                    refreshedPoints, changedPoints);

            LOG.info("      of which {} changed to added edges, {} to baseline edges, and {} became unlinked.",
                    changedToAddedEdge, changedToBaselineEdge, changedToUnlinked);
        }
        // dumpLinkagesToWkt();
    }

    /** @return the number of linkages, which should be the same as the number of points in the PointSet. */
    public int size () {
        return edges.length;
    }

    /**
     * A functional interface for fetching the accumulated cost (time or distance) to any street vertex in the
     * transport network. Note that TIntIntMap::get matches this functional interface. There may be a generic
     * IntToIntFunction library interface somewhere, but this interface provides type information about what the
     * function and its parameters mean.
     * TODO wrap with StreetRouterResult class that specifies whether costs are distance or time.
     */
    @FunctionalInterface
    public static interface CostToVertexFunction {
        /**
         * @param vertexId the index of a vertex in the StreetLayer of a TransitNetwork.
         * @return the accumulated cost to the given street vertex, or Integer.MAX_VALUE if the vertex is unreachable.
         */
        public int getCost(int vertexId);
    }


    @Deprecated
    public PointSetTimes eval (CostToVertexFunction travelTimeForVertex) {
        // R5 used to not differentiate between seconds and meters, preserve that behavior in this deprecated function
        // by using 1 m / s
        return eval(travelTimeForVertex, 1000, 1000, null);
    }

    /**
     * Calculate the total time needed to reach every point in this pointset (e.g. from an origin when evaluating
     * direct, non-transit travel times). The total time includes time from origin split to vertices of destination
     * edge, vertex of destination edge to destination split, and destination split to destination.
     *
     * @param timeToVertex function returning the time required to reach a vertex, in seconds
     * @param onStreetSpeed speed at which the first/last edge is traversed, in millimeters per second. If this
     *                      linkage is for CAR, the destination edge's car speed will override the supplied
     *                      onStreetSpeed.
     * @param offStreetSpeed travel speed between the first/last edge and origin/target, in millimeters per
     *                       second. Generally walking (we don't account for off-street parking not specified in OSM)
     * @return wrapped int[] of travel times (in seconds) to reach the pointset points, with Integer.MAX_VALUE for
     * unreached points.
     */

    public PointSetTimes eval (
                CostToVertexFunction timeToVertex,
                Integer onStreetSpeed,
                int offStreetSpeed,
                Split origin
            ) {
        int[] travelTimes = new int[edges.length];
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        for (int i = 0; i < edges.length; i++) {
            if (edges[i] < 0) {
                // Target point is unlinked.
                travelTimes[i] = Integer.MAX_VALUE;
                continue;
            }

            edge.seek(edges[i]);

            if (streetMode == StreetMode.CAR) {
                onStreetSpeed = (int) (edge.getCarSpeedMetersPerSecond() * 1000);
            }

            if (origin != null && origin.edge == edges[i]) {
                // The target point lies along the same edge as the origin
                int onStreetDistance_mm = Math.abs(origin.distance0_mm - distances0_mm[i]);
                travelTimes[i] = // origin.distanceToEdge_mm / offStreetSpeed + TODO origin to origin split point
                                onStreetDistance_mm / onStreetSpeed + // along street
                                distancesToEdge_mm[i] / offStreetSpeed; // from destination split point to destination
            } else {
                travelTimes[i] = timeToPoint(timeToVertex, edge, i, onStreetSpeed);
            }
        }
        return new PointSetTimes(pointSet, travelTimes);
    }

    /**
     * Given a table of distances to street vertices from a particular transit stop, create a table of distances to
     * points in this PointSet from the same transit stop. All points outside the distanceTableZone are skipped as an
     * optimization. See JavaDoc on the caller: this is one of the slowest parts of building a network.
     *
     * This is a pure function i.e. it has no side effects on the state of the LinkedPointSet instance. For the
     * moment it is used only for walk distance tables, which are computed once and saved when the network is built.
     *
     * @param distanceTableToVertices a map from integer vertex IDs to cumulative distance (in millimeters) accrued
     *                                traversing the network from the stop to the vertices
     * @param distanceTableZone the envelope in FIXED POINT DEGREES within which we want to find all points.
     * @return A packed array of (pointIndex, cost), or null if there are no reachable points.
     */
    public int[] extendDistanceTableToPoints(TIntIntMap distanceTableToVertices, Envelope distanceTableZone) {
        return extendCostsToPoints(distanceTableToVertices::get,
                RoutingVariable.DISTANCE_MILLIMETERS,
                distanceTableZone,
                null);
    }

    /**
     * Given a table of costs (time or distance) to street vertices from a particular transit stop, create a table of
     * costs to points in this PointSet from the same transit stop. All points outside the distanceTableZone are
     * skipped as an optimization. See JavaDoc on the caller (EgressCostTable): this is one of the slowest parts of
     * building a network.
     *
     * This is a pure function i.e. it has no side effects on the state of the LinkedPointSet instance.
     *
     * @param costToVertex method to get the travel time or distance for a given vertex id
     * @param routingVariable whether the costToVertex method returns distances (millimeters) or times (seconds)
     * @param envelopeAroundStop the envelope in FIXED POINT DEGREES within which we want to find all points.
     * @param egressArea area served by on-demand service from this stop. If null, there are no restrictions on which
     *                  points can be reached in the egress leg from this stop.
     * @return A packed array of (pointIndex, cost), or null if there are no reachable points. Cost units match
     * supplied sr.routingVariable
     */
    public int[] extendCostsToPoints(CostToVertexFunction costToVertex,
                                     RoutingVariable routingVariable,
                                     Envelope envelopeAroundStop,
                                     Geometry egressArea) {
        int nPoints = this.size();
        TIntIntMap costToPoint = new TIntIntHashMap(nPoints, 0.5f, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Edge edge = streetLayer.edgeStore.getCursor();
        // We may not even need a distance table zone: we could just skip all points whose vertices are not in the router result.
        TIntList relevantPoints = pointSet.getPointsInEnvelope(envelopeAroundStop);
        // This is not correcting for the fact that the method returns false positives, but that should be harmless.
        // It's returning every point in the bounding box. But it is also sensitive to which vertices are in the map.
        relevantPoints.forEach(p -> {
            // An edge index of -1 for a particular point indicates that this point is unlinked.
            if (edges[p] == -1) {
                return true; // Continue to next iteration.
            }

            if (egressArea != null && !GeometryUtils.containsPoint(egressArea, pointSet.getLon(p), pointSet.getLat(p))) {
                return true; // Point is outside supplied area, continue to next iteration.
            }

            edge.seek(edges[p]);

            int cost = Integer.MAX_VALUE;

            if (routingVariable == RoutingVariable.DISTANCE_MILLIMETERS) {
                cost = distanceToPoint(costToVertex, edge, p);
            } else if (routingVariable == RoutingVariable.DURATION_SECONDS) {
                // The routing variable is seconds only if we're doing a car search, so look up the car speed on the
                // linked edge.
                int onStreetSpeed = (int) (edge.getCarSpeedMetersPerSecond() * 1000);
                cost = timeToPoint(costToVertex, edge, p, onStreetSpeed);
            }

            if (cost != Integer.MAX_VALUE) {
                costToPoint.put(p, cost);
            }
            return true; // Continue iteration.
        });
        if (costToPoint.size() == 0) {
            return null;
        }
        // Convert a packed array of pairs.
        // TODO don't put in a list and convert to array, just make an array.
        TIntList packed = new TIntArrayList(costToPoint.size() * 2);
        costToPoint.forEachEntry((point, cost) -> {
            packed.add(point);
            packed.add(cost);
            return true; // Continue iteration.
        });
        return packed.toArray();
    }

    /** For debugging purposes, write all the linkages out as CSV separated WKT which can be loaded into QGIS. */
    public void dumpLinkagesToWkt () {
        // Dump all linkages as WKT CSV for QGIS
        try (FileWriter writer = new FileWriter("linkage.wkt.csv")) {
            for (int p = 0; p < edges.length; p++) {
                int edgeIndex = edges[p];
                if (edgeIndex < 0) continue;
                double pointLat = pointSet.getLat(p);
                double pointLon = pointSet.getLon(p);
                Edge edge = streetLayer.edgeStore.getCursor(edgeIndex);
                VertexStore.Vertex fromVertex = streetLayer.vertexStore.getCursor(edge.getFromVertex());
                VertexStore.Vertex toVertex = streetLayer.vertexStore.getCursor(edge.getToVertex());
                String wkt = String.format(
                        "%d, \"LINESTRING (%f %f, %f %f, %f %f)\"\n", p,
                        fromVertex.getLon(), fromVertex.getLat(),
                        pointLon, pointLat,
                        toVertex.getLon(), toVertex.getLat()
                );
                writer.write(wkt);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Given accumulated distances to vertices, add the remaining distance needed to reach a point that is linked to
     * an edge. This remaining distance consists of a distance along part of the edge (to the "split point"), plus a
     * perpendicular distance from the split point to the final point.
     *
     * @param costToVertex function returning the accumulated distance to reach a given vertex
     * @param edge to which the target is linked
     * @param pointIndex index of the point in this linkage
     * @return minimum distance needed to reach point, or Integer.MAX_VALUE if point is not reachable.
     */
    private int distanceToPoint(CostToVertexFunction costToVertex, Edge edge, int pointIndex) {
        // TODO this is not strictly correct when there are turn restrictions onto the edge this is linked to
        int distance0 = costToVertex.getCost(edge.getFromVertex());
        int distance1 = costToVertex.getCost(edge.getToVertex());
        if (distance0 == Integer.MAX_VALUE && distance1 == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else {
            distance0 += distances0_mm[pointIndex] + distancesToEdge_mm[pointIndex];
            distance1 += distances1_mm[pointIndex] + distancesToEdge_mm[pointIndex];
            return Math.min(handleOverflow(distance0), handleOverflow(distance1));
        }
    }

    /**
     * Given accumulated time to reach vertices, add the remaining time needed to reach a point that is linked to
     * an edge. This remaining time consists of a time traversing part of the edge (at onStreetSpeed), plus a
     * time needed to travel the perpendicular distance (at offStreetSpeed) from the split point to the linked point.
     *
     * @param costToVertex function returning the accumulated time to reach a given vertex
     * @param edge to which the target is linked
     * @param pointIndex index of the point in this linkage
     * @param onStreetSpeed speed at which the destination edge (to which the target is linked) is traversed
     * @return minimum time needed to reach point, or Integer.MAX_VALUE if point is not reachable.
     */
    private int timeToPoint(CostToVertexFunction costToVertex, Edge edge, int pointIndex, int onStreetSpeed) {
        int time0 = costToVertex.getCost(edge.getFromVertex());
        int time1 = costToVertex.getCost(edge.getToVertex());
        if (time0 == Integer.MAX_VALUE && time1 == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else {
            int offStreetTime = distancesToEdge_mm[pointIndex] / OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
            time0 += distances0_mm[pointIndex] / onStreetSpeed + offStreetTime;
            time1 += distances1_mm[pointIndex] / onStreetSpeed + offStreetTime;
            return Math.min(handleOverflow(time0), handleOverflow(time1));
        }
    }

    private int handleOverflow (int value) {
        return value < 0 ? Integer.MAX_VALUE : value;
    }

}
