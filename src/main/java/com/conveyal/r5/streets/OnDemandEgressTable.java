package com.conveyal.r5.streets;

import com.conveyal.gtfs.flex.MeetingAreas;
import com.conveyal.r5.analyst.progress.NoopProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.common.GeometryUtils;
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

import static com.conveyal.r5.streets.EgressCostTable.CAR_TIME_LINKING_LIMIT_SECONDS;
import static com.conveyal.r5.streets.EgressCostTable.MAX_CAR_SPEED_METERS_PER_SECOND;
import static com.conveyal.r5.streets.LinkedPointSet.OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;
import static com.conveyal.r5.profile.StreetMode.CAR;
import static com.google.common.base.Preconditions.checkArgument;

/// Travel times of on-demand rides from transit stops to destination points for propagation. This
/// is the expensive-to-prepare, request-independent half of on-demand egress. The other half,
/// everything that depends on the request or a particular service, is evaluated in
/// PerTargetPropagater.
///
/// Unlike EgressCostTable, which covers every transit stop for one street mode, this table is
/// sparse. Rows exist only for stops where some on-demand service can pick up a transit rider. The
/// assumption is that most stops in most networks are not served by an on-demand service.
/// Currently, on-demand egress is also slightly different than normal car egress in that it may
/// include a short walk component to reach the pick-up point. Eventually the two should be
/// unified.
///
/// Like other egress tables, rows are computed asynchronously by the NetworkPreloader before any
/// search runs, never during a search.
///
/// Each destination PointSet linked to each distinct street network needs its own instance of this
/// class.
///
/// Per-stop rows are not clipped to any on-demand drop-off place because they are reused between
/// services, including any services added later by scenario modifications. The pick-up side of a
/// row is also service-independent. MeetingAreas are a property of the stop, not of any service
///
/// The stored times never exceed the same car linking limit as EgressCostTable, which bounds the
/// search; the propagator further bounds each ride by the total trip duration and the service's
/// drop-off window, never by the rider's street leg time limit (an on-demand ride is transit usage,
/// not a rider street leg).
public class OnDemandEgressTable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final LinkedPointSet linkedPointSet;

    /// Values for one stop as three parallel arrays indexed on destination point number.
    /// Rows exist only briefly until they are transposed and merged into the point-major snapshot.
    private record Row (int[] points, int[] walkMillimeters, int[] carSeconds) { }

    /// Stands in for the row of a stop whose search reached nothing.
    private static final Row EMPTY_ROW = new Row(new int[0], new int[0], new int[0]);

    /// The stops whose rows have been computed and merged into the snapshot.
    /// Avoids repeatedly recomputing rows for new scenarios.
    private final TIntSet computedStops = new TIntHashSet();

    /// The number of ints describing each ride in the point-major arrays below.
    public static final int INTS_PER_RIDE = 3;

    /// The transposed (destination-point-major) form. For each point, the rides reaching it as
    /// packed (stop, walk millimeters, car seconds) triples, or null where no stop reaches the
    /// point. Published for lock-free reads during propagation and replaced when new rows added.
    /// The inner arrays are never modified after publication so a reader holding any published
    /// outer array sees consistent data.
    private volatile int[][] triplesForPoint;

    public OnDemandEgressTable (LinkedPointSet linkedPointSet) {
        checkArgument(linkedPointSet.streetMode == CAR,
            "On-demand egress cost tables represent car-like travel and require a CAR linkage.");
        this.linkedPointSet = linkedPointSet;
    }

    /// Ensure rows exist for all the given stops, computing any missing ones in parallel and
    /// republishing the point-major table when rows were added. Computing rows can take a long time
    /// so this is normally called by the NetworkPreloader with all stops in the network's
    /// OnDemandEgressIndex. There is a fallback for tests and regional tasks with freeform
    /// destinations, which the preloader skips.
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
    /// car seconds) triples, or null when no stop with an on-demand egress row reaches the point.
    /// May overselect stops for which no service is active for the current request.
    /// The returned array must not be modified.
    public int[] ridesForPoint (int pointIndex) {
        return triplesForPoint[pointIndex];
    }

    /// Find travel times from one stop to all destination points within the car linking limit.
    /// This includes a walk to each MeetingArea vertex followed by car travel, minimized together
    /// at the typical walk speed, with the winning path's two portions recorded separately.
    private Row computeRow (int stop) {
        TransitLayer transitLayer = linkedPointSet.streetLayer.parentNetwork.transitLayer;
        Point stopPoint = transitLayer.getJTSPointForStopFixed(stop);
        if (stopPoint == null) {
            return EMPTY_ROW; // The stop is not linked to the street network.
        }
        TIntIntMap meetingArea = linkedPointSet.streetLayer.parentNetwork.meetingAreas()
            .areaWithDistances(stop);
        if (meetingArea.isEmpty()) {
            return EMPTY_ROW; // No drivable street in reach, on-demand cannot serve this stop.
        }
        StreetRouter router = new StreetRouter(linkedPointSet.streetLayer);
        router.streetMode = CAR;
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        router.timeLimitSeconds = CAR_TIME_LINKING_LIMIT_SECONDS;
        router.setOrigins(meetingArea, OFF_STREET_SPEED_MILLIMETERS_PER_SECOND);
        router.route();
        // Total seconds (walk plus car) and the walked distance alone, for every reached vertex.
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
            CAR_TIME_LINKING_LIMIT_SECONDS * MAX_CAR_SPEED_METERS_PER_SECOND
                + MeetingAreas.MEETING_AREA_RADIUS_METERS);
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
        TIntList cars = new TIntArrayList();
        EdgeStore.Edge edge = linkedPointSet.streetLayer.edgeStore.getCursor();
        TIntList relevantPoints = linkedPointSet.pointSet.getPointsInEnvelope(envelopeAroundStop);
        relevantPoints.forEach(p -> {
            if (linkedPointSet.edges[p] == -1) {
                return true; // Point is unlinked, continue iteration.
            }
            edge.seek(linkedPointSet.edges[p]);
            int onStreetSpeed = (int) (edge.getCarSpeedMetersPerSecond() * 1000);
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
            cars.add(total - walkSeconds);
            return true;
        });
        if (points.isEmpty()) {
            return EMPTY_ROW;
        }
        return new Row(points.toArray(), walks.toArray(), cars.toArray());
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
                extended[extended.length - 1] = row.carSeconds[i];
                merged[point] = extended;
            }
        }
        triplesForPoint = merged;
    }

}
