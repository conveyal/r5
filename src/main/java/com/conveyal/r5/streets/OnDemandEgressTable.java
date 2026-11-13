package com.conveyal.r5.streets;

import com.conveyal.gtfs.flex.MeetingAreas;
import com.conveyal.r5.analyst.progress.NoopProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.stream.IntStream;

import static com.conveyal.r5.streets.EgressCostTable.BICYCLE_EGRESS_DISTANCE_LIMIT_METERS;
import static com.conveyal.r5.streets.EgressCostTable.CAR_EGRESS_TIME_LIMIT_SECONDS;
import static com.conveyal.r5.streets.EgressCostTable.MAX_CAR_SPEED_METERS_PER_SECOND;
import static com.conveyal.r5.streets.LinkedPointSet.OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
import static com.conveyal.r5.profile.StreetMode.BICYCLE;
import static com.conveyal.r5.profile.StreetMode.CAR;
import static com.google.common.base.Preconditions.checkArgument;

/// Travel times of on-demand rides from transit stops to destination points for propagation. This
/// is the expensive-to-prepare, request-independent part of on-demand egress. The other part,
/// everything that depends on the request or a particular service, is evaluated in
/// PerTargetPropagater.
///
/// Each table holds rides on one street mode, the mode of the LinkedPointSet it is attached to.
/// It serves the on-demand services whose vehicles have that mode, which is CAR or BICYCLE. A
/// CAR ride is stored at the speed limit of each street. A BICYCLE ride is stored at a fixed
/// reference speed and is rescaled to the bike speed of each request during propagation, which
/// keeps the rows independent of the request.
///
/// Unlike EgressCostTable, which covers every transit stop for one street mode, this table is
/// sparse. Rows exist only for stops where some on-demand service of this table's mode can pick up
/// a transit rider. The assumption is that most stops in most networks are not served by an
/// on-demand service. Currently, on-demand egress is also slightly different than normal car or
/// bicycle egress in that it may include a short walk component to reach the pick-up point.
/// Eventually the two should be unified.
///
/// Like other egress tables, rows are computed asynchronously by the NetworkPreloader before any
/// search runs, never during a search.
///
/// Each destination PointSet linked to each distinct street network needs its own instance of this
/// class for each street mode used by on-demand vehicles.
///
/// Per-stop rows are not clipped to any on-demand drop-off place because they are reused between
/// services of the same mode, including any services added later by scenario modifications. The
/// pick-up side of a row is also service-independent. MeetingAreas are a property of the stop and
/// the mode, not of any service.
///
/// The stored times never exceed the per-mode egress search limit that EgressCostTable uses,
/// which bounds the search. The propagator further bounds each ride by the total trip duration and
/// the service's drop-off window, but not by the rider's street leg time limit (an on-demand ride
/// is considered transit usage, not a street leg).
///
/// The traffic stress limit of a request cannot be applied to BICYCLE rows, because they are
/// computed once for all requests. They are computed with no limit. On the access side the ride
/// search does apply the request's limit, so the two sides can differ for a bicycle service.
public class OnDemandEgressTable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// The speed at which BICYCLE rides are stored. OnDemandEgress rescales the stored ride
    /// seconds by the ratio of this speed to the bike speed of the request.
    public static final float REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND = 4;

    private final LinkedPointSet linkedPointSet;

    /// The street mode of the vehicles whose rides this table holds, CAR or BICYCLE.
    private final StreetMode vehicleMode;

    /// Values for one stop as three parallel arrays indexed on destination point number.
    /// Rows exist only briefly until they are transposed and merged into the point-major snapshot.
    private record Row (int[] points, int[] walkMillimeters, int[] rideSeconds) { }

    /// Stands in for the row of a stop whose search reached nothing.
    private static final Row EMPTY_ROW = new Row(new int[0], new int[0], new int[0]);

    /// The stops whose rows have been computed and merged into the snapshot.
    /// Avoids repeatedly recomputing rows for new scenarios.
    private final TIntSet computedStops = new TIntHashSet();

    /// The number of ints describing each ride in the point-major arrays below.
    public static final int INTS_PER_RIDE = 3;

    /// The transposed (destination-point-major) form. For each point, the rides reaching it as
    /// packed (stop, walk millimeters, ride seconds) triples, or null where no stop reaches the
    /// point. Published for lock-free reads during propagation and replaced when new rows added.
    /// The inner arrays are never modified after publication so a reader holding any published
    /// outer array sees consistent data.
    private volatile int[][] triplesForPoint;

    public OnDemandEgressTable (LinkedPointSet linkedPointSet) {
        checkArgument(linkedPointSet.streetMode == CAR || linkedPointSet.streetMode == BICYCLE,
            "On-demand vehicles are CAR or BICYCLE, so their egress cost tables require a linkage for one of those modes.");
        this.linkedPointSet = linkedPointSet;
        this.vehicleMode = linkedPointSet.streetMode;
    }

    /// Ensure rows exist for all the given stops, computing any missing ones in parallel and
    /// republishing the point-major table when rows were added. Computing rows can take a long time
    /// so this is normally called by the NetworkPreloader with all stops in the network's
    /// OnDemandEgressIndex for this table's mode. There is a fallback for tests and regional
    /// tasks with freeform destinations, which the preloader skips.
    public synchronized void ensureStops (TIntSet stops) {
        ensureStops(stops, new NoopProgressListener());
    }

    public synchronized void ensureStops (TIntSet stops, ProgressListener progressListener) {
        TIntSet missing = new TIntHashSet();
        stops.forEach(stop -> {
            if (!computedStops.contains(stop)) missing.add(stop);
            return true;
        });
        if (missing.isEmpty() && triplesForPoint != null) return;
        int[] missingStops = missing.toArray();
        Arrays.sort(missingStops);
        Row[] rows = new Row[missingStops.length];
        if (missingStops.length > 0) {
            progressListener.beginTask("Building on-demand egress tables", missingStops.length);
            LambdaCounter counter = new LambdaCounter(LOG, missingStops.length, 100,
                "Computed on-demand egress rows for {} of {} transit stops.");
            rows = IntStream.range(0, missingStops.length).parallel()
                .mapToObj(i -> {
                    Row row = computeRow(missingStops[i]);
                    counter.increment();
                    progressListener.increment();
                    return row;
                }).toArray(Row[]::new);
            counter.done();
        }
        mergeRows(missingStops, rows);
        computedStops.addAll(missing);
    }

    /// Returns the on-demand rides reaching the given point as packed (stop, walk millimeters,
    /// ride seconds) triples, or null when no stop with an on-demand egress row reaches the point.
    /// For a BICYCLE table the ride seconds are at the reference bicycle speed.
    /// May overselect stops for which no service is active for the current request.
    /// The returned array must not be modified.
    public int[] ridesForPoint (int pointIndex) {
        return triplesForPoint[pointIndex];
    }

    /// An upper bound in seconds on the rides this table stores. The search from each stop ends
    /// at this duration, so no stored ride exceeds it. This is also a bound on the EgressCostTable
    /// for the same mode. The bicycle bound there is a distance, which we convert to a duration at
    /// the reference bicycle speed.
    private int rideTimeLimitSeconds () {
        if (vehicleMode == BICYCLE) {
            return (int) (BICYCLE_EGRESS_DISTANCE_LIMIT_METERS / REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND);
        }
        return CAR_EGRESS_TIME_LIMIT_SECONDS;
    }

    /// An upper bound in meters on the distance a ride in this table can cover within the time
    /// bound, used only to limit which destination points are examined for each stop.
    private int rideDistanceLimitMeters () {
        if (vehicleMode == BICYCLE) {
            return BICYCLE_EGRESS_DISTANCE_LIMIT_METERS;
        }
        return CAR_EGRESS_TIME_LIMIT_SECONDS * MAX_CAR_SPEED_METERS_PER_SECOND;
    }

    /// Find travel times from one stop to all destination points within the egress search limit for
    /// this table's mode. This includes a walk to each MeetingArea vertex followed by a ride,
    /// minimized together at the typical walk speed, with the winning path's two portions
    /// recorded separately.
    private Row computeRow (int stop) {
        TransitLayer transitLayer = linkedPointSet.streetLayer.parentNetwork.transitLayer;
        Point stopPoint = transitLayer.getJTSPointForStopFixed(stop);
        if (stopPoint == null) {
            return EMPTY_ROW; // The stop is not linked to the street network.
        }
        TIntIntMap meetingArea = linkedPointSet.streetLayer.parentNetwork.meetingAreas(vehicleMode)
            .areaWithDistances(stop);
        if (meetingArea.isEmpty()) {
            // No street allowing the vehicle mode in reach, on-demand cannot serve this stop.
            return EMPTY_ROW;
        }
        StreetRouter router = new StreetRouter(linkedPointSet.streetLayer);
        router.streetMode = vehicleMode;
        // The router has its own new request. Its bike speed is set explicitly so that BICYCLE
        // rows do not depend on the default value in ProfileRequest. CAR rides ignore this speed.
        router.profileRequest.bikeSpeed = REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND;
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        router.timeLimitSeconds = rideTimeLimitSeconds();
        router.setOrigins(meetingArea, OFF_STREET_SPEED_MILLIMETERS_PER_SECOND);
        router.route();
        // Total seconds (walk plus ride) and the walked distance alone, for every reached vertex.
        // Each state's chain of back states ends at the initial state at the meeting vertex where
        // its path began, whose distance field holds exactly the walked distance to that vertex.
        TIntIntMap totalForVertex = new TIntIntHashMap(1024, 0.5f, -1, Integer.MAX_VALUE);
        TIntIntMap walkMmForVertex = new TIntIntHashMap(1024, 0.5f, -1, 0);
        router.getReachedVertices().forEachKey(vertex -> {
            StreetRouter.State state = router.getStateAtVertex(vertex);
            if (state != null) {
                totalForVertex.put(vertex, state.getDurationSeconds());
                walkMmForVertex.put(vertex, initialWalkMillimeters(state));
            }
            return true;
        });
        // The router records states by their back edges, so at each initial vertex the initial
        // state (which did not arrive via any edge) is not among the reached states. Only costlier
        // arrivals looping back over the vertex's edges are. Add the initial values into both maps
        // so rides can begin at each those initial vertices.
        meetingArea.forEachEntry((vertex, distanceMm) -> {
            int walkSeconds = distanceMm / OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
            if (walkSeconds < totalForVertex.get(vertex)) {
                totalForVertex.put(vertex, walkSeconds);
                walkMmForVertex.put(vertex, distanceMm);
            }
            return true;
        });
        Envelope envelopeAroundStop = stopPoint.getEnvelopeInternal();
        GeometryUtils.expandEnvelopeFixed(envelopeAroundStop,
            rideDistanceLimitMeters() + MeetingAreas.MEETING_AREA_RADIUS_METERS);
        return extendToPoints(totalForVertex, walkMmForVertex, envelopeAroundStop);
    }

    /// Return the walked distance in millimeters at the beginning of the given state's path,
    /// read from the first state in its chain of back states, which setOrigins created at a
    /// meeting vertex with the walked distance to that vertex in its distance field.
    private static int initialWalkMillimeters (StreetRouter.State state) {
        while (state.backState != null) {
            state = state.backState;
        }
        return state.distance;
    }

    /// Extend per-vertex costs out to the points of the linkage, as LinkedPointSet's eval and
    /// extendCostsToPoints do, but recording the walk portion of the vertex yielding the best path.
    private Row extendToPoints (TIntIntMap totalForVertex, TIntIntMap walkMmForVertex, Envelope envelopeAroundStop) {
        TIntList points = new TIntArrayList();
        TIntList walks = new TIntArrayList();
        TIntList rides = new TIntArrayList();
        EdgeStore.Edge edge = linkedPointSet.streetLayer.edgeStore.getCursor();
        TIntList relevantPoints = linkedPointSet.pointSet.getPointsInEnvelope(envelopeAroundStop);
        relevantPoints.forEach(p -> {
            if (linkedPointSet.edges[p] == -1) {
                return true; // Point is unlinked, continue iteration.
            }
            edge.seek(linkedPointSet.edges[p]);
            float onStreetMetersPerSecond = (vehicleMode == CAR)
                ? edge.getCarSpeedMetersPerSecond()
                : REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND;
            int onStreetSpeed = (int) (onStreetMetersPerSecond * 1000);
            int offStreetSeconds = linkedPointSet.distancesToEdge_mm[p] / OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
            int total0 = LinkedPointSet.timeViaVertex(totalForVertex.get(edge.getFromVertex()),
                linkedPointSet.distances0_mm[p], onStreetSpeed, offStreetSeconds);
            int total1 = LinkedPointSet.timeViaVertex(totalForVertex.get(edge.getToVertex()),
                linkedPointSet.distances1_mm[p], onStreetSpeed, offStreetSeconds);
            int total = Math.min(total0, total1);
            if (total == Integer.MAX_VALUE) {
                return true; // Point is unreachable, continue iteration.
            }
            int winningVertex = (total0 <= total1) ? edge.getFromVertex() : edge.getToVertex();
            int walkMillimeters = walkMmForVertex.get(winningVertex);
            int walkSeconds = walkMillimeters / OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
            points.add(p);
            walks.add(walkMillimeters);
            rides.add(total - walkSeconds);
            return true;
        });
        if (points.isEmpty()) {
            return EMPTY_ROW;
        }
        return new Row(points.toArray(), walks.toArray(), rides.toArray());
    }

    /// Merge freshly computed rows into the point-major table and publish the result. Merge uses
    /// copy-on-write approach and Rows are expected to be discarded after this operation.
    /// With no rows this still publishes an array of nulls avoiding a null check in table readers.
    private void mergeRows (int[] stops, Row[] rows) {
        int[][] merged = (triplesForPoint == null)
            ? new int[linkedPointSet.size()][] : triplesForPoint.clone();
        for (int r = 0; r < stops.length; r++) {
            Row row = rows[r];
            int stop = stops[r];
            for (int i = 0; i < row.points.length; i++) {
                int point = row.points[i];
                int[] existing = merged[point];
                int[] extended;
                if (existing == null) {
                    extended = new int[INTS_PER_RIDE];
                } else {
                    extended = Arrays.copyOf(existing, existing.length + INTS_PER_RIDE);
                }
                extended[extended.length - 3] = stop;
                extended[extended.length - 2] = row.walkMillimeters[i];
                extended[extended.length - 1] = row.rideSeconds[i];
                merged[point] = extended;
            }
        }
        triplesForPoint = merged;
    }

}
