package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.cluster.PathResult.Iteration;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.path.PatternSequence;
import com.conveyal.r5.util.TIntIntHashMultimap;
import com.conveyal.r5.util.TIntIntMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.TIntCollection;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static com.conveyal.r5.common.GeometryUtils.envelopeForCircle;
import static com.conveyal.r5.common.GeometryUtils.polygonForEnvelope;
import static com.google.common.base.Preconditions.checkState;

/*

Implementation considerations:

- Shapes are one of the biggeset parts of GTFS feeds.
- TransitLayer can associate shapes with each TripPattern and extract the sub-shapes between each stop.
- However this functionality is hard-wired to be disabled (with a constant SAVE_SHAPES) during network build.
- This means all existing TransportNetworks do not have shapes attached to their TripPatterns.
- Enabling this across the board is expected to make all TransitLayers significantly larger.

- Every single path generated needs to be subject to filtering.
- Geographic intersections can be quite slow.
- The geographic intersections need to be precomputed and looked up quickly during routing.

- The SAVE_SHAPES section of TransitLayer building is relatively complicated.
- It invovles projecting stops onto shapes and splitting them, and must handle cases where shapes are missing.
- We don't want to replicate this existing logic elsewhere.

GtfsController accesses GeometryCache in GtfsCache.patternShapes, but this just saves entire exemplar trip geometries,
not inter-stop segments. TripPattern.getHopGeometries looks relatively simple using LocationIndexedLine, but depends on
some fairly complicated stop-to-shape snapping logic in the SAVE_SHAPES section of TransitLayer to pre-build fields.
We could either re-run this code after the fact to inject the shapes into existing network files, or we could enable it
with a network build time switch. We need to turn on shape storage in the TripPatterns, or otherwise iterate through
all of them in a streaming fashion to record every one that passes through the bounding box.

TripPattern does make the assumption that all trips on the same pattern have the same geometry (or can be reasonably
represented with the same geometry drawn from one of the trips).

In existing serialized TransitLayers, TripPattern.getHopGeometries usually returns straight lines because
TripPattern.shape is null (it is hard-wired to not save shapes in TransitLayers). However, the GTFS MapDBs so still
contain the shapes for each trip. This is how we show them in GtfsController making VectorMapTiles. We already have
spatial index capabilities at gtfsCache.patternShapes.queryEnvelope(bundleScopedFeedId, tile.envelope). See L206-209 of
GtfsController. However, this does not retain enough information about the segments between stops in the patterns, and
uses a lot of space for all those geometries.

Networks are always built and scenarios always applied on workers. Workers do have access to GTFSFeed files.
WorkerComponents has a TransportNetworkCache which is injected into the AnalysisWorker constructor. This is the only
path to access a GtfsCache, and that GtfsCache is private, so we need methods on TransportNetworkCache. The full path
to this GtfsCache is: AnalysisWorker.networkPreloader.transportNetworkCache.gtfsCache.

The best way to prototype the intended behavior is to create a new modification type. This provides a mechanism for
attaching things to a network, at a point where we may still have access to the GTFS feeds. It also ensures that the
network with this extra information is properly cached for similar subsequent requests (as in a regional analysis).
We can't attach the precomputed selected link information to the raw base TransportNetwork, because then the first
request for that network would always need to be one with the selected-link behavior specified. Networks are expected
to be read-only once loaded, and anyway subsequent requests for the same network hit a cache and don't pass through
the right place to access the GTFSCache and update the in-memory network. We need to be able to apply it later to a
network that was already loaded without the selected link specified. We could treat the network as mutable and write to
it, but this does not follow existing design and would require mentally modeling how to manupulate the system to get
the desired effect.

TransportNetworkCache#getNetworkForScenario is where we always apply scenarios in the worker, and that class has direct
access to the GtfsCache.

Deciding whether to create SelectedLink via a Modification or a per-request parameter:

The SelectedLink instance (fast checking whether paths pass through an area) needs to be stored/referenced:
- Somewhere that is reachable from inside PathResult.setTarget or PathResult.summarizeIterations
- Somewhere that is correctly scoped to where the selected-link filtering is specified (request/task or scenario)
- Somewhere that is writable in the places where we have access to the gtfsCache
- Somewhere that is PERSISTENT across requests - this is inherently the case for TransportNetwork but for Task we'd
  need to introduce another cache. The problem being that the base TransportNetwork's scope is too wide (could be
  used in requests with or without the SelectedLink), so it needs to be a modification on a specific scenario.

The PathResult constructor is passed a Task and a TransitLayer. It retains only the TransitLayer but could retain both.
In AnalysisWorker.handleAndSerializeOneSinglePointTask we still have the task context, but deeper on the stack in
networkPreloader and then transportNetworkCache (which has gtfsCache), we have the scenario but not the task. But
then once you go deeper into applying the individual scenario modifications, the gtfsCache is no longer visible.

SelectedLink doesn't feel like a modification. It feels like a parameter to the CSV path output in the task.
The AnalysisWorker could have a Map from SelectionBox to SelectedLink, but then the keys are full of floating-point
coordinate numbers, which requires fuzzy matching on these keys to look up the precomputed data. This could get very
ugly.

We also need to tie pre-existin items in the TransportNetwork (TripPatterns) to new items from the GTFS. It feels like
this should be on a scenario copy of a TransportNetwork. It's ugly, but it would be possible to scan over the incoming
modifications and inject the GtfsCache (or pre-selected GTFSFeeds) onto a transient field of any SelectedLink
Modification present.

Getting this information into the network resulting from applying a Scenario makes it auto-retained, gives it a stable
identity so we don't need to fuzzy-match it in the task to cache. That could also be done by uploading a geometry file
with an ID, but that's so much indirection for a single small polygon. In the future it would make sense to treat all
lat/lon as effectively integers (fixed-point) since it simplifies this kind of keying and matching.

Alternatively we could enable on the storage of GTFS route shapes on the network file when it's built. Then the
modification could be applied normally without injecting a GtfsCache or GtfsFeeds. But again that bloats the size of
every network just for the odd case where someone wants to do selected link analysis.

Bundle Scoping problem:

The feed IDs expected by gtfsCache (i.e. gtfs file names) are bundle-scoped but the ones in the TripPatterns are not.
TransportNetworks and TransitLayers apparently do not retain their bundle ID. In any case they can have multiple feeds
originally uploaded with different bundles. TransitLayer.feedChecksums keys are the same feed IDs prefixing
TripPattern.routeId, which are the gtfsFeed.feedId, which is not bundle-scoped so can't be used to get a feed from
gtfsCache.

A network is always based on one bundle with the same ID, but the bundle config can also reference GTFS with a
different bundle scope (originally uploaded for another bundle). So knowing the network ID is not sufficient to find
a GTFS feed from its un-scoped UUID.

Based on GtfsController.bundleScopedFeedIdFromRequest, the bundleScopedFeedId is feedId_feedGroupId. They're no longer
based on the bundle/network ID, but the feed group. It seems like we wouldn't need these scopes at all since all feeds
now have unique IDs. Removing them could cause a lot of disruption though.

When we make the TransportNetwork from these bundles, it's always on a worker, using information from the bundle's
TransportNetworkConfig JSON file. This is in TransportNetworkCache.buildNetworkFromConfig(). At first it looks like
the bundleScopedId is completely lost after we go through the loading process. GtfsCache.get(String id) does store
that key id in feed.uniqueId, but that field is never read (or written) anywhere else.

This means the bundle ID is available during network creation to be retained in the TransportNetwork, but they aren't
retained. I think the only place we can get these bundle scoped feed IDs is from the TransportNetworkConfig JSON file.
Perhaps that should be serialized into the TransportNetwork itself (check the risk of serializing applied Modifications).
But in the meantime TNCache has a method to load that configuration and get the bundle scopes.

The distinction between stop indexes at the TransitLayer level and stop indexes (positions) within the TripPattern is
critical and can cause some comparisons to fail silently, while superficially appearing to do something meaningful.
The lightweight newtype pattern would be really useful here but doesn't exist in Java. It is not practical to change
the source path information we receive such that it stores stop indexes within the TripPattern and defers resolution
to TransitLayer indexes and names. Looking at the Path constructor and RaptorState, we don't have this information as
RaptorStates are stored in array slots indexed on stop indexes from the TransitLayer (not the TripPattern level ones).

 */

/**
 * This class is used in performing "selected link" analysis (R5 issue #913). It retains a precomputed collection
 * containing every segment of every TripPattern that passes through a certain polygon, and provides methods for quickly
 * checking whether any leg of a public transit trip overlaps these precomputed segments.
 */
public class SelectedLink {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** A unique identifier distinguishing this SelectedLink from all others being used at the same time. */
    public final String label;

    /**
     * Contains all TripPattern inter-stop hops that pass through the selected link area for fast hash-based lookup.
     * Keys are the index of a TripPattern in the TransitLayer, and values are arrays of stop positions within that
     * TripPattern (NOT the stop index within the TransitLayer). A hop from stop position N to stop position N+1 on
     * pattern at index X is recorded as the mapping X -> N. There may be several such hops within a single pattern,
     * thus an array with more than one element, in the case where one or more transit stops along the pattern fall
     * within the SelectedLink search radius.
     */
    private final TIntObjectMap<int[]> hopsInTripPattern;

    /**
     * The TransitLayer relative to which all TripPattern indexes and stop indexes should be interpreted.
     * This is the TransitLayer of the TransportNetwork that holds this SelectedLink instance.
     * It should be treated as strictly read-only.
     * Applying further scenarios could perhaps cause the two references to diverge, but the information here in the
     * base TransitLayer should remain fixed and valid for interpreting hopsInTripPattern.
     */
    private final TransitLayer transitLayer;

    public SelectedLink(String label, TransitLayer transitLayer, TIntObjectMap<int[]> hopsInTripPattern) {
        this.label = label;
        this.transitLayer = transitLayer;
        this.hopsInTripPattern = hopsInTripPattern;
    }

    /**
     * For a given transit ride from a board stop to an alight stop on a TripPattern, return true if that ride
     * passes through any of the hops in this SelectedLink area, or false if it's entirely outside the area.
     * This is complicated by the fact that the board and alight stops are TransitLayer-wide stop indexes,
     * not the position of the stop within the pattern. It is possible (though unlikely) that a board and alight
     * stop pair could ambiguously refer to more than one sub-segment of the same pattern when one of the stops appears
     * more than once in the pattern's stop sequence. We find the earliest matching sub-segment in the sequence.
     */
    private boolean includes (int pattern, int board, int alight) {
        int[] hops = hopsInTripPattern.get(pattern);
        // Short-circuit: bail out early from most comparisons when the trip pattern has no hops in the SelectedLink.
        if (hops == null) {
            return false;
        }
        // Less common case: one or more hops in the pattern of this transit leg do fall inside this SelectedLink.
        // Determine at which positions in the pattern the board and alight stops are located. Begin looking for
        // the alight position after the board position, imposing order constraints and reducing potential for
        // ambiguity where stops appear more than once in the same pattern.
        int boardPos = stopPositionInPattern(pattern, board, 0);
        int alightPos = stopPositionInPattern(pattern, alight, boardPos + 1);
        for (int hop : hops) {
            // Hops are identified with the stop position at their beginning so the alight comparison is exclusive:
            // a leg alighting at a stop does not ride over the hop identified with that stop position.
            if (boardPos <= hop && alightPos > hop) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translate a stop index within the TransitLayer to a stop position within the TripPattern with the given index.
     */
    private int stopPositionInPattern (int patternIndex, int stopIndexInTransitLayer, int startingAtPos) {
        TripPattern tripPattern = transitLayer.tripPatterns.get(patternIndex);
        for (int s = startingAtPos; s < tripPattern.stops.length; s++) {
            if (tripPattern.stops[s] == stopIndexInTransitLayer) {
                return s;
            }
        }
        String message = String.format("Did not find stop %d in pattern %d", stopIndexInTransitLayer, patternIndex);
        throw new IllegalArgumentException(message);
    }

    /**
     * Check whether the given PatternSequence has at least one transit leg that passes through this SelectedLink area.
     */
    private boolean traversedBy (PatternSequence patternSequence) {
        // Why are some patterns TIntLists null? Are these walk-only routes with no transit legs?
        if (patternSequence.patterns == null) {
            return false;
        }
        // Iterate over the three parallel arrays containing TripPattern, board stop, and alight stop indexes.
        for (int ride = 0; ride < patternSequence.patterns.size(); ride++) {
            int pattern = patternSequence.patterns.get(ride);
            int board = patternSequence.stopSequence.boardStops.get(ride);
            int alight = patternSequence.stopSequence.alightStops.get(ride);
            if (this.includes(pattern, board, alight)) {
                // logTriple(pattern, board, alight);
                return true;
            }
        }
        return false;
    }

    /**
     * This filters a particular type of Multimap used in PathResult and TravelTimeReducer.recordPathsForTarget().
     * For a single origin-destination pair, it captures all transit itineraries connecting that origin and destination.
     * The keys represent sequences of transit rides between specific stops (TripPattern, board stop, alight stop).
     * The values associated with each key represent individual raptor iterations that used that sequence of rides,
     * each of which may have a different departure time, wait time, and total travel time. This method returns a
     * filtered COPY of the supplied Multimap, with all mappings removed for keys that do not pass through this
     * SelectedLink area. This often yields an empty Multimap, greatly reducing the number of rows in the CSV output.
     */
    public Multimap<PatternSequence, Iteration> filterPatterns (Multimap<PatternSequence, Iteration> patterns) {
        Multimap<PatternSequence, Iteration> filteredPatterns = HashMultimap.create();
        for (PatternSequence patternSequence : patterns.keySet()) {
            if (this.traversedBy(patternSequence)) {
                Collection<Iteration> iterations = patterns.get(patternSequence);
                filteredPatterns.putAll(patternSequence, iterations);
            }
        }
        return filteredPatterns;
    }

    private void logTriple (int pattern, int boardStop, int alightStop) {
        String routeId = transitLayer.tripPatterns.get(pattern).routeId;
        String boardStopName  = transitLayer.stopNames.get(boardStop);
        String alightStopName  = transitLayer.stopNames.get(alightStop);
        LOG.info("Route {} from {} to {}", routeId, boardStopName, alightStopName);
    }
}
