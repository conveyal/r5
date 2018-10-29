package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.ScenarioCache;
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
 * See #381.
 *
 * We don't want to just make all the caches (network, grid, linkage, etc.) asynchronous because
 * we don't want a first request to only asynchronously load the network, then a second one to apply the scenario and
 * trigger the distance table computation, then a third one to link the destination grids for a particular mode.
 * We want all the steps to be done at one go. Therefore we want a single asynchronous action at the top level, which
 * cascades a lot of sequential synchronous actions interspersed with progress updates.
 *
 * The distance table construction is done as part of the scenario application. So our main steps are:
 * fetch the network, apply the scenario, build the linkages.
 *
 * The incoming request may not have a scenario ID in it. TransportNetwork.getNetworkForScenario() resolves it as
 * follows: String scenarioId = request.scenarioId != null ? request.scenarioId : request.scenario.id;
 *
 * I was initially trying to do this with a Caffeine AsyncLoadingCache, but ran into the following problems:
 * 1. The cache values must be computed from the key only. But expanded scenarios may be contained in the request.
 * 2. We want to provide progress information, and retrofitting the Caffeine cache with it would be just as complicated
 *    as making our own new class.
 * 3. We don't really want to trigger actions after the asynchronous loading is finished, we just want to know
 *    immediately at the time of each call whether it's finished building or not.
 *
 * The steps that need to happen are the following:
 * 1. Download and load the network if it's already built.
 * 2. Build the network if it's not already built.
 * 3. Apply the scenario to the network, or fetch the resulting network if the scenario is already applied.
 * 3. Pre-compute stop-to-vertex distance tables and linkages (stop-to-point distance tables) for the walking mode.
 * 4. Create or fetch a grid of destinations based on this specific task and the gridCache.
 * 5. Link that grid to the street network with the right mode, basing distances on the stop-to-vertex tables created above.
 *
 * Repeatedly retrying a full task just to see if it fails seems like a bad way to report progress.
 * We may want to enqueue tasks like we do in data tools, with progress reporting. That's the only thing that's going
 * to give people a sense that it's good enough.
 *
 * However that's the first step to getting this solved - the nested progress tracking and reporting part can come later.
 *
 * A tricky thing is how to handle the question: is it available now or not? If it is available,
 * Maybe you do want to kick off the process, block for half a second waiting on the result, then report where you're at.
 *
 * Perhaps the values should be a wrapper type bundling together all the data loaded all at once by the preloader.
 * Our current design is somewhat suboptimal in that all of these items are in different caches.
 * The distance tables or linkages could be evicted for example.
 *
 * Created by abyrd on 2018-09-17
 */
public class DataPreloader extends AsyncLoader<DataPreloader.Key, TransportNetwork> {

    private static final Logger LOG = LoggerFactory.getLogger(DataPreloader.class);

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    public final TransportNetworkCache transportNetworkCache;

    /**
     * A table of already seen scenarios, avoiding downloading them repeatedly from S3 and allowing us to replace
     * scenarios with only their IDs, and reverse that replacement later.
     */
    private final ScenarioCache scenarioCache = new ScenarioCache();

    public DataPreloader(TransportNetworkCache transportNetworkCache) {
        this.transportNetworkCache = transportNetworkCache;
    }

    // TODO make synchronous option / method for regional analysis

    public Response<TransportNetwork> preloadData (AnalysisTask task) {
        if (task.scenario != null) {
            transportNetworkCache.rememberScenario(task.scenario);
        }
        return get(Key.forTask(task));
    }

    @Override
    protected TransportNetwork buildValue(Key key) {

        // First get the network, apply the scenario, and (re)build distance tables.
        // Those steps should eventually be pulled out of the cache loaders to make progress reporting more granular.
        setProgress(key, 1, "Building network...");
        TransportNetwork scenarioNetwork = transportNetworkCache.getNetworkForScenario(key.networkId, key.scenarioId);

        // Get the set of points to which we are measuring travel time. TODO handle multiple destination grids.
        setProgress(key, 50, "Fetching gridded point set...");
        PointSet pointSet = AnalysisTask.gridPointSetCache.get(key.webMercatorExtents, scenarioNetwork.gridPointSet);

        // Then rebuild grid linkages as needed
        setProgress(key, 51, "Linking grids to the street network and finding distances...");
        for (StreetMode mode : key.modes) {
            // Finer grained progress indicator:
            // int percentage = 50D/nModes * i / 50;
            pointSet.link(scenarioNetwork.streetLayer, mode);
        }

        // Finished building all needed inputs for analysis, return the completed network to the AsyncLoader code.
        return scenarioNetwork;
    }

    /**
     * The items that uniquely identify which set of data is needed to perform an analysis.
     * Namely: the network ID, the scenario ID, and the modes of transport.
     */
    public static class Key {

        public final String networkId;
        public final String scenarioId;
        public final WebMercatorExtents webMercatorExtents; // rename to destination grid extents
        public final EnumSet<StreetMode> modes; // Actually, looks like this should be StreetMode, why are requests using LegMode?
        // Final key element is the destination grids?

        /**
         * If a destination opportunity grid is present in the request - not a grid ID but the actual grid, then the
         * destination extents will be taken from that grid. This should only be the case for non-static-site regional
         * tasks. The grid ID should have been resolved to a grid object before calling this method.
         *
         * If no grid object is present, then the destination extents will be taken from the task.
         *
         * This follows our intended behavior: regional tasks that compute accessibility indicators find paths to all
         * the points in some specified opportunity density grid. All other requests (single-point time surfaces and
         * static sites) find paths to all the points in the extents specified in the task.
         *
         * That is to say, the extents specified in the task are the destination points of a single point request,
         * are both the origin and destination points of a static site, and are only the origins of a regional
         * accessibility task, where the destinations are given by the specified opportunity grid.
         */
        public Key (AnalysisTask task) {
            this.networkId = task.graphId;
            // A supplied scenario ID will always override any full scenario that is present.
            this.scenarioId = task.scenarioId != null ? task.scenarioId : task.scenario.id;
            // We need to link for all of access modes, egress modes, and direct modes (depending on whether transit is used).
            // See code in TravelTimeComputer for when each is used.
            this.modes = EnumSet.of(
                    LegMode.getDominantStreetMode(task.directModes),
                    LegMode.getDominantStreetMode(task.accessModes),
                    LegMode.getDominantStreetMode(task.egressModes));

            if (task.isHighPriority() || task.makeStaticSite) {
                // High Priority is an obsolete term for "single point task".
                // For single point tasks and static sites, there is no opportunity grid. The grid of destinations is
                // the extents given in the task, which for static sites is also the grid of origins.
                this.webMercatorExtents = WebMercatorExtents.forTask(task);
            } else {
                // A non-static-site regional task. We expect a valid grid of opportunities to be specified as the
                // destinations. This is necessary to compute accessibility. So we extract those bounds from the grids.
                this.webMercatorExtents = WebMercatorExtents.forGrid(((RegionalTask)task).gridData);
            }

            /**
             * Travel time results may be combined with many different grids, so we don't want to limit their geographic extent
             * to that of any one grid. Instead we use the extents supplied in the request.
             * The UI only sends these if the user has changed them to something other than "full region".
             * If "full region" is selected, the UI sends nothing and the backend fills in the bounds of the region.
             *
             * FIXME: the request bounds indicate either origin bounds or destination bounds depending on the request type.
             * We need to specify these separately as we merge all the request types.
             *
             * Reuse linkages in the base gridPointSet stored in the TransportNetwork as to avoid relinking
             *
             * Regional analyses use the extents of the destination opportunity grids as their destination extents.
             * We don't want to enqueue duplicate tasks with the same destination pointset extents, because it is more efficient
             * to compute travel time for a given destination only once, then accumulate multiple accessibility values
             * for multiple opportunities at that destination.
             */
            // In the special case where we're making a static site, a regional task is producing travel time grids.
            // This is unlike the usual case where regional tasks produce accessibility indicator values.
            // Because the time grids are not intended for one particular set of destinations,
            // they should cover the whole analysis region. This RegionalTask has its own bounds, which are the bounds
            // of the origin grid.
            // FIXME the following limits the destination grid bounds to be exactly those of the origin grid.
            // This could easily be done with pointSets.add(network.gridPointSet);
            // However we might not always want to search out to such a huge destination grid.

            // This is a regional analysis that is not a static site.
            // A single grid is specified.
            // Use the network point set as the base point set, so that the cached linkages are used
            // The GridCache is in AnalystWorker and used to be passed in as a method parameter.
        }

        public static Key forTask(AnalysisTask task) {
            return new Key(task);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key other = (Key) o;
            return Objects.equals(networkId, other.networkId) &&
                    Objects.equals(scenarioId, other.scenarioId) &&
                    Objects.equals(webMercatorExtents, other.webMercatorExtents) &&
                    Objects.equals(modes, other.modes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(networkId, scenarioId, webMercatorExtents, modes);
        }
    }

    /**
     * All the data items needed to perform an analysis. The value associated with a Key.
     * This ensures we have references to them all at once, they aren't going to be evicted from some cache once we
     * have them in hand.
     * TODO we should cache this (flatter) instead of just the TransportNetwork, but that's a later refactor.
     */
    public static class PreloadedData {
        /** Network with the requested scenario applied and stop-to-vertex distance tables built. */
        public final TransportNetwork network;
        public final Grid grid;
        public final LinkedPointSet linkedPointSet;
        public PreloadedData(TransportNetwork network, Grid grid, LinkedPointSet linkedPointSet) {
            this.network = network;
            this.grid = grid;
            this.linkedPointSet = linkedPointSet;
        }
    }

    /**
     * Stopgap measure to load
     */
    public TransportNetwork loadNetworkBlocking (String networkId) {
        return this.transportNetworkCache.getNetwork(networkId);
    }

}
