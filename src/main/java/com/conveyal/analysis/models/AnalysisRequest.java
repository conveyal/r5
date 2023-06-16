package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.ChaosParameters;
import com.conveyal.r5.analyst.decay.DecayFunction;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.mongodb.QueryBuilder;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Request sent from the UI to the backend. It is actually distinct from the task that the broker
 * sends/forwards to R5 workers (see {@link AnalysisWorkerTask}), though it has many of the same fields.
 */
public class AnalysisRequest {

    /**
     * These three IDs are redundant, and just help reduce the number of database lookups necessary.
     * The bundleId and modificationIds should be considered the definitive source of truth (regionId and projectId are
     * implied by the bundleId and the modification Ids). Behavior is undefined if the API caller sends inconsistent
     * information (a different regionId or projectId than the one the bundleId belongs to).
     */
    public String regionId;
    public String projectId;
    public String scenarioId;

    public String bundleId;
    public List<String> modificationIds = new ArrayList<>();
    public String workerVersion;

    public String accessModes;
    public float bikeSpeed;
    public Bounds bounds;
    public LocalDate date;
    public String directModes;
    public String egressModes;
    public float fromLat;
    public float fromLon;
    public float toLat;
    public float toLon;
    public int fromTime;
    public int monteCarloDraws = 200;
    public int toTime;
    public String transitModes;
    public float walkSpeed;
    public int maxTripDurationMinutes = 120;
    public int maxRides = 4;
    public int[] percentiles;
    public int[] cutoffsMinutes;
    public int maxWalkTime = 20;
    public int maxBikeTime = 20;

    /** Web Mercator zoom level; 9 (~250 m by ~250 m) is standard. */
    public int zoom = 9;

    // Parameters that aren't currently configurable in the UI =====================================

    public int bikeTrafficStress = 4;
    public float carSpeed = 20;
    public int maxCarTime = 45;
    public int minBikeTime = 10;
    public int minCarTime = 10;
    public int streetTime = 90;
    public int suboptimalMinutes = 5;

    /**
     * Whether the R5 worker should log an analysis request it receives from the broker.
     * analysis-backend translates front-end requests to the format expected by R5. To debug this
     * translation process, set logRequest = true in the front-end profile request, then look for
     * the full request received by the worker in its log.
     */
    public boolean logRequest = false;

    // Multi-origin only ===========================================================================

    /** The human-readable name for a regional analysis. */
    public String name;

    /**
     * Set of points to use as origins, from which to calculate travel times or accessibility. If
     * this is not specified, all raster cells of the Web Mercator Grid implied by {@link
     * AnalysisRequest#bounds} will be used as origins in an accessibility analysis, resulting in an
     * accessibility grid. If an originPointSetId is specified, the server will look up the full key
     * for the pointset, and the points in the pointset will be used as origins for a travel time or
     * accessibility analysis.
     */
    public String originPointSetId;

    /**
     * The IDs of pointsets to be used as destinations in accessibility or travel time calculations. This can be
     * one or more grids with identical extents, or a single freeform pointset.
     * This replaces the deprecated singular opportunityDatasetId.
     * This field is required for regional analyses, which always compute accessibility to destinations.
     * On the other hand, in a single point request this may be null, in which case the worker will report only
     * travel times to destinations and not accessibility figures.
     */
    public String[] destinationPointSetIds;

    /** Whether to save all results in a regional analysis to S3 for display in a "static site". */
    public boolean makeTauiSite = false;

    /**
     * Whether to record travel times between origins and destinations.
     * If true, requires an originPointSetId to be specified.
     */
    public boolean recordTimes;

    /**
     * Whether to record path and travel time details
     */
    public boolean recordPaths;

    /**
     * Whether to record travel time from each origin to a one corresponding destination (the
     * destination at the same position in the destionationPointSet). This is relevant for the
     * travel-time reporting functions triggered if recordTimes is true, not for the originally
     * developed core accessibility calculations in Analysis.
     */
    public boolean oneToOne;

    /** Whether to record cumulative opportunity accessibility indicators for each origin. */
    public boolean recordAccessibility = true;

    // For multi-criteria optimization (Pareto search on time and fare cost) =======================

    /**
     * A fare calculator instance to use when computing accessibility constrained by both time and
     * monetary cost. Different classes are instantiated based on the "name" field before having
     * their properties set. See JsonSubType annotations on InRoutingFareCalculator class.
     */
    public InRoutingFareCalculator inRoutingFareCalculator;

    /** Limit on monetary expenditure on fares when an inRoutingFareCalculator is used. */
    public int maxFare;

    /**
     * A function mapping travel times to weighting factors for opportunities at that travel time from the origin.
     * Classic cumulative opportunities accessibility uses a step function with all opportunities below the cutoff
     * weighted 1.0 and all opportunities at or above the cutoff weighted 0.
     */
    public DecayFunction decayFunction;

    /**
     * If this field is non-null, it will intentionally cause failures on workers handling the task. This is done on
     * testing or even production systems in order to observe and improve their robustness to failure.
     */
    public ChaosParameters injectFault;

    /**
     * Create the R5 `Scenario` from this request.
     */
    public Scenario createScenario (UserPermissions userPermissions) {
        QueryBuilder query = "all".equals(scenarioId)
                ? QueryBuilder.start("projectId").is(projectId)
                : QueryBuilder.start("_id").in(modificationIds);
        Collection<Modification> modifications = Persistence.modifications.findPermitted(query.get(), userPermissions);
        // `findPermitted` sorts by creation time by default. Nonces will be in the same order each time.
        String nonces = Arrays.toString(modifications.stream().map(m -> m.nonce).toArray());
        String scenarioId = String.format("%s-%s", bundleId, DigestUtils.sha1Hex(nonces));
        Scenario scenario = new Scenario();
        scenario.id = scenarioId;
        scenario.modifications = modifications.stream().map(com.conveyal.analysis.models.Modification::toR5).collect(Collectors.toList());
        return scenario;
    }

    /**
     * Finds the modifications for the specified project and variant, maps them to their
     * corresponding R5 modification types, creates a checksum from those modifications, and adds
     * them to the AnalysisTask along with the rest of the request.
     * <p>
     * This method takes a task as a parameter, modifies that task, and also returns that same task.
     * This is because we have two subtypes of AnalysisTask and need to be able to create both.
     *
     * This populates for a single-point task, and several things get overwritten for regional tasks.
     *
     * TODO arguably this should be done by a method on the task classes themselves, with common parts factored out
     *      to the same method on the superclass.
     */
    public void populateTask (AnalysisWorkerTask task, UserPermissions userPermissions) {
        if (bounds == null) throw AnalysisServerException.badRequest("Analysis bounds must be set.");

        task.scenario = createScenario(userPermissions);
        task.scenarioId = task.scenario.id;
        task.graphId = bundleId;
        task.workerVersion = workerVersion;
        task.maxFare = maxFare;
        task.inRoutingFareCalculator = inRoutingFareCalculator;

        // TODO define class with static factory function WebMercatorGridBounds.fromLatLonBounds().
        //      Also include getIndex(x, y), getX(index), getY(index), totalTasks()
        WebMercatorExtents extents = WebMercatorExtents.forWgsEnvelope(bounds.envelope(), zoom);
        task.height = extents.height;
        task.north = extents.north;
        task.west = extents.west;
        task.width = extents.width;
        task.zoom = zoom;

        task.date = date;
        task.fromLat = fromLat;
        task.fromLon = fromLon;
        task.toLat = toLat;
        task.toLon = toLon;
        task.fromTime = fromTime;
        task.toTime = toTime;
        task.includePathResults = recordPaths;
        task.bikeSpeed = bikeSpeed;
        task.carSpeed = carSpeed;
        task.walkSpeed = walkSpeed;

        task.bikeTrafficStress = bikeTrafficStress;
        task.maxWalkTime = maxWalkTime;
        task.maxBikeTime = maxBikeTime;
        task.maxCarTime = maxCarTime;
        task.maxRides = maxRides;
        if (task.inRoutingFareCalculator != null) {
            // Only overwrite the default cutoff when doing multi-criteria routing with fare constraints, which can be
            // slow and reliant on lower temporal cutoffs to avoid timeouts.
            // In previous versions, the default cutoff was also overwritten for non-Taui regional analyses. But
            // with changes introduced for decay functions/multiple cutoffs, that step is no longer needed here.
            task.maxTripDurationMinutes = maxTripDurationMinutes;
        }
        task.minBikeTime = minBikeTime;
        task.minCarTime = minCarTime;
        task.streetTime = streetTime;
        task.suboptimalMinutes = suboptimalMinutes;

        task.monteCarloDraws = monteCarloDraws;
        task.percentiles = percentiles;
        task.cutoffsMinutes = cutoffsMinutes;
        
        task.logRequest = logRequest;

        task.accessModes = getEnumSetFromString(accessModes);
        task.directModes = getEnumSetFromString(directModes);
        task.egressModes = getEnumSetFromString(egressModes);
        task.transitModes = transitModes != null && !"".equals(transitModes)
                ? EnumSet.copyOf(Arrays.stream(transitModes.split(",")).map(TransitModes::valueOf).collect(Collectors.toList()))
                : EnumSet.noneOf(TransitModes.class);

        // Use the decay function supplied by the UI, defaulting to a zero-width step function if none is supplied.
        task.decayFunction = decayFunction;
        if (task.decayFunction == null) {
            task.decayFunction = new StepDecayFunction();
        }
        // Intentionally introduce errors for testing purposes, but only for admin users.
        if (injectFault != null) {
            if (userPermissions.admin) {
                task.injectFault = injectFault;
            } else {
                throw new IllegalArgumentException("Must be admin user to inject faults.");
            }
        }
    }

    private EnumSet<LegMode> getEnumSetFromString (String s) {
        if (s != null && !"".equals(s)) {
            return EnumSet.copyOf(Arrays.stream(s.split(",")).map(LegMode::valueOf).collect(Collectors.toList()));
        } else {
            return EnumSet.noneOf(LegMode.class);
        }
    }
}
