package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.progress.NetworkPreloaderProgressListener;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.util.AsyncLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Objects;

/**
 * This either returns a network that can be immediately used for a certain analysis task (if it's already prepared)
 * or kicks off an asynchronous task to prepare such a network, and reports status to the caller. See #381.
 *
 * The steps that need to happen to prepare a network for analysis are the following:
 * 1. Download and load the network if it's already built.
 * 2. Build the network if it's not already built.
 * 3. Apply the scenario to the network, or fetch the resulting network if the scenario is already applied.
 * 3. Pre-compute stop-to-vertex distance tables and linkages (stop-to-point distance tables) for the walking mode.
 * 4. Create or a destination pointset based on this specific task and the pointSetCache.
 * 5. Link that pointset to the street network with the right mode, basing distances on the stop-to-vertex tables
 * created above.
 *
 * The network building is done as part of requesting the network.
 * The distance tables are constructed as part of the scenario application. So the main steps to trigger explicitly are:
 * 1. Fetch the network
 * 2. Apply the scenario
 * 3. Build the linkages
 *
 * Implementation notes:
 *
 * We don't want to just make all the caches (network, grid, linkage, etc.) asynchronous because we don't want the first
 * HTTP request to only asynchronously load the network, then the second one to apply the scenario and trigger the
 * distance table computation, then the third one to link the destination grids for a particular mode. We want all the
 * steps to be done at one go. Therefore we want a single asynchronous action at the top level, which cascades a lot of
 * sequential synchronous actions interspersed with progress updates.
 *
 * The incoming request may not have a scenario ID in it, in which case it should contain the entire scenario.
 * TransportNetwork.getNetworkForScenario() resolves it as follows:
 * String scenarioId = request.scenarioId != null ? request.scenarioId : request.scenario.id;
 *
 * I was initially trying to implement this with a Caffeine AsyncLoadingCache, but ran into the following problems:
 * 1. The cache values must be computed from the key only. But expanded scenarios may be contained in the request.
 * 2. We want to provide progress information, and retrofitting the Caffeine cache with it would be just as complicated
 *    as making our own new class.
 * 3. We don't really want to trigger callbacks after the asynchronous loading is finished, we just want to know
 *    immediately at the time of each HTTP request whether it's finished building or not.
 *
 * Repeatedly re-sending a full Analysis task over HTTP just to see if the network is ready (and failing if it's not)
 * seems like an inefficient way to report progress. But it is a good incremental step within the current architecture.
 * We may want to enqueue tasks like we do in Data Tools, with continuous progress reporting. That's the only thing
 * that will truly meet user expectations, but will require a task tracking mechanism and API, so that's another issue.
 *
 * One tricky thing is that the network may be mostly available and need only one small action, e.g. applying a very
 * simple scenario. Ideally we'd block for a short time (e.g. one second) waiting on the prepared network, then only
 * report progress if that operation times out.
 *
 * Currently we're returning only the network, and the rest of the needed data are nested inside that network in
 * caches (which can suffer eviction). Perhaps this class should instead prepare and return a wrapper type that bundles
 * together all the needed data (network, distance tables, linkages) loaded by the preloader.
 *
 * Created by abyrd on 2018-09-17
 */
public class NetworkPreloader extends AsyncLoader<NetworkPreloader.Key, TransportNetwork> {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkPreloader.class);

    /** Keeps one or more TransportNetworks around, lazy-loading or lazy-building them. */
    public final TransportNetworkCache transportNetworkCache;

    public NetworkPreloader(TransportNetworkCache transportNetworkCache) {
        this.transportNetworkCache = transportNetworkCache;
    }

    public LoaderState<TransportNetwork> preloadData (AnalysisWorkerTask task) {
        if (task.scenario != null) {
            transportNetworkCache.rememberScenario(task.scenario);
        }
        return get(Key.forTask(task));
    }

    @Override
    protected TransportNetwork buildValue(Key key) {

        // First get the network, apply the scenario, and (re)build distance tables.
        // Those steps should eventually be pulled out of the cache loaders to make progress reporting more granular.
        setProgress(key, 0, "Building network...");
        TransportNetwork scenarioNetwork = transportNetworkCache.getNetworkForScenario(key.networkId, key.scenarioId);

        // Get the set of points to which we are measuring travel time. Any smaller sub-grids created here will
        // reference the scenarioNetwork's built-in full-extent pointset, so can reuse its linkage.
        // TODO handle multiple destination grids.
        setProgress(key, 0, "Fetching gridded point set...");
        PointSet pointSet = AnalysisWorkerTask.gridPointSetCache.get(key.destinationGridExtents, scenarioNetwork.fullExtentGridPointSet);

        // Now rebuild grid linkages as needed. One linkage per mode, and one cost table per egress mode.
        // Cost tables are slow to compute and not needed for access or direct legs, only egress modes.
        // Note that we're able to pass a progress listener down into the EgressCostTable contruction process,
        // but not into the linkage process, because the latter is encapsulated as a Google/Caffeine
        // LoadingCache. We'll need some way to get LoadingCache's per-key locking, while still allowing a
        // progress listener specific to the single request. Perhaps this will mean registering 0..N
        // progressListeners per key in the cache. It may be a good idea to keep progressListener objects in
        // fields on Factory classes rather than passing them as parameters into constructors or factory methods.
        for (StreetMode mode : key.allModes) {
            setProgress(key, 0, "Linking destination grid to streets for " + mode + "...");
            LinkedPointSet linkedPointSet = scenarioNetwork.linkageCache
                    .getLinkage(pointSet, scenarioNetwork.streetLayer, mode);
            if (key.egressModes.contains(mode)) {
                ProgressListener progressListener = new NetworkPreloaderProgressListener(this, key);
                linkedPointSet.getEgressCostTable(progressListener);
            }
        }
        // Finished building all needed inputs for analysis, return the completed network to the AsyncLoader code.
        return scenarioNetwork;
    }

    /**
     * A compound key containing all items uniquely identifying the set of data is needed to perform an analysis.
     * Namely: the network ID, the scenario ID, the extents of the grid, and the modes of on-street transport.
     * A final key element could be the destination density grids, but those are relatively quick to load.
     */
    public static class Key {

        public final String networkId;
        public final String scenarioId;
        public final WebMercatorExtents destinationGridExtents;
        public final EnumSet<StreetMode> allModes;
        public final EnumSet<StreetMode> egressModes;

        /**
         * If a destination opportunity grid is present in the request - not a grid ID but the actual grid object,
         * then the destination extents should be taken from that grid. This should only be the case for
         * non-static-site regional tasks. The grid ID should have been resolved to a grid object before calling
         * this constructor. If no grid object is present, then the destination extents should be taken from the
         * task.
         *
         * This follows our intended behavior: regional tasks that compute accessibility indicators find paths to all
         * the points in some specified opportunity density grid. All other requests (single-point time surfaces and
         * static sites) find paths to all the points in the extents specified in the task.
         *
         * That is to say, the extents specified in the task are the destination points of a single point request,
         * are both the origin and destination points of a static site, and are only the origins of a regional
         * accessibility task, where the destinations are given by the specified opportunity grid.
         *
         * Travel time results (single point or static sites) may be combined with any number of different grids,
         * so we don't want to limit their geographic extent to that of any one grid. Instead we use the extents
         * supplied in the request. The UI only sends these if the user has changed them to something other than
         * "full region". If "full region" is selected, the UI sends nothing and the backend fills in the bounds of
         * the region.
         *
         * FIXME but should we really be injecting Grid objects into a class deserialized straight from JSON?
         */
        public Key (AnalysisWorkerTask task) {
            this.networkId = task.graphId;
            // A supplied scenario ID will always override any full scenario that is present.
            this.scenarioId = task.scenarioId != null ? task.scenarioId : task.scenario.id;
            // We need to link for all of access modes, egress modes, and direct modes (depending on whether
            // transit is used). See code in TravelTimeComputer for when each is used.
            // Egress modes must be tracked independently since we need to build EgressDistanceTables for those.
            this.allModes = LegMode.toStreetModeSet(task.directModes, task.accessModes, task.egressModes);
            this.egressModes = LegMode.toStreetModeSet(task.egressModes);
            this.destinationGridExtents = task.getWebMercatorExtents();
        }

        public static Key forTask(AnalysisWorkerTask task) {
            return new Key(task);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key other = (Key) o;
            return Objects.equals(networkId, other.networkId) &&
                    Objects.equals(scenarioId, other.scenarioId) &&
                    Objects.equals(destinationGridExtents, other.destinationGridExtents) &&
                    Objects.equals(allModes, other.allModes) &&
                    Objects.equals(egressModes, other.egressModes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(networkId, scenarioId, destinationGridExtents, allModes, egressModes);
        }
    }

    /**
     * All the data items needed to perform an analysis. This should eventually be the value associated with a Key,
     * ensuring we have references to them all at once and they aren't going to be evicted from some cache once we
     * have them in hand.
     * TODO we should cache this instead of just the TransportNetwork.
     *      i.e. this class should extend AsyncLoader<NetworkPreloader.Key, PreloadedData>
     */
    public static class PreloadedData {
        /** Network with the requested scenario applied and stop-to-vertex distance tables built. */
        public final TransportNetwork network;
        public final Grid grid;
        public final LinkedPointSet linkedPointSet;
        public PreloadedData (TransportNetwork network, Grid grid, LinkedPointSet linkedPointSet) {
            this.network = network;
            this.grid = grid;
            this.linkedPointSet = linkedPointSet;
        }
    }

}
