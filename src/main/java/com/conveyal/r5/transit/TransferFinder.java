package com.conveyal.r5.transit;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 *     // TODO combine TransferFinder with stop tree builder.
 */
public class TransferFinder {

    private static Logger LOG = LoggerFactory.getLogger(TransferFinder.class);

    // Optimization: use the same empty list for all stops with no transfers
    private static final TIntArrayList EMPTY_INT_LIST = new TIntArrayList();

    TransitLayer transitLayer;

    StreetLayer streetLayer;

    StreetRouter streetRouter;

    public int radiusMeters = 1000;

    /**
     * Should chooses whether to search via the street network or straight line distance based on the presence of
     * OSM street data (whether the street layer is null). However the street layer will always contain transit
     * stop vertices so not sure that can work.
     */
    public TransferFinder(TransportNetwork network) {
        this.transitLayer = network.transitLayer;
        this.streetLayer = network.streetLayer;
        streetRouter = new StreetRouter(streetLayer);
        streetRouter.distanceLimitMeters = radiusMeters;
    }

    public void findTransfers () {
        LOG.info("Finding transfers through the street network from all stops...");
        // For each stop, store all transfers out of that stop as packed pairs of (toStopIndex, distance)
        final List<TIntList> transfersForStop = transitLayer.transfersForStop;
        // When applying scenarios we want to find transfers for only the newly added stops.
        // We look at any existing list of transfers and do enough iterations to make it as long as the list of stops.
        int firstStopIndex = transfersForStop.size();
        for (int s = firstStopIndex; s < transitLayer.getStopCount(); s++) {
            // From each stop, run a street search looking for other transit stops.
            int originStreetVertex = transitLayer.streetVertexForStop.get(s);
            if (originStreetVertex == -1) {
                LOG.warn("Stop {} is not connected to the street network.", s);
                // Every iteration must add an array to transfersForStop to maintain the right length.
                transfersForStop.add(EMPTY_INT_LIST);
                continue;
            }
            streetRouter.setOrigin(originStreetVertex);
            streetRouter.route();
            TIntIntMap timesToReachedStops = streetRouter.getReachedStops();
            retainClosestStopsOnPatterns(timesToReachedStops);
            // At this point we have the distances to all stops that are the closest one on some pattern.
            // Make transfers to them, packed as pairs of (target stop index, distance).
            TIntList packedTransfers = new TIntArrayList();
            timesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
                packedTransfers.add(targetStopIndex);
                packedTransfers.add(distance);
                return true;
            });
            // Record this list of transfers as leading out of the stop with index s.
            if (packedTransfers.size() > 0) {
                transfersForStop.add(packedTransfers);
            } else {
                transfersForStop.add(EMPTY_INT_LIST);
            }
            // If we are applying a scenario (extending the transfers list rather than starting from scratch), for
            // all transfers out of a scenario stop into a base network stop we must also create the reverse transfer.
            // The original packed transfers list is copied on write to avoid perturbing the base network.
            if (firstStopIndex > 0) {
                final int originStopIndex = s; // Why oh why, Java?
                timesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
                    TIntList packedTransfersCopy = new TIntArrayList(transfersForStop.get(targetStopIndex));
                    packedTransfersCopy.add(originStopIndex);
                    packedTransfersCopy.add(distance);
                    transfersForStop.set(targetStopIndex, packedTransfersCopy);
                    return true;
                });
            }

        }
        // Store the transfers in the transit layer
        transitLayer.transfersForStop = transfersForStop;
        LOG.info("Done finding transfers.");
    }


    /**
     * Filter down a map from target stop indexes to distances so it only includes those stops that are the
     * closest on some pattern. This is technically incorrect (think of transfers to a U shaped metro from a bus line
     * running down the middle of the U vertically, a situation which actually exists in Washington, DC) but
     * anecdotally it speeds up computation by up to 40 percent. We may want to look into other ways to optimize
     * transfers (or why the transfers are making routing so much slower) if this turns out to affect results.
     */
    private void retainClosestStopsOnPatterns(TIntIntMap timesToReachedStops) {
        TIntIntMap bestStopOnPattern = new TIntIntHashMap(50, 0.5f, -1, -1);
        // For every reached stop,
        timesToReachedStops.forEachEntry((stopIndex, timeToStop) -> {
            // For every pattern passing through that stop,
            transitLayer.patternsForStop.get(stopIndex).forEach(patternIndex -> {
                int currentBestStop = bestStopOnPattern.get(patternIndex);
                // Record this stop if it's the closest one yet seen on that pattern.
                if (currentBestStop == -1) {
                    bestStopOnPattern.put(patternIndex, stopIndex);
                } else {
                    int currentBestTime = timesToReachedStops.get(currentBestStop);
                    if (currentBestTime > timeToStop) {
                        bestStopOnPattern.put(patternIndex, stopIndex);
                    }
                }
                return true; // iteration should continue
            });
            return true; // iteration should continue
        });
        timesToReachedStops.retainEntries((stop, time) -> bestStopOnPattern.containsValue(stop));
    }

    /**
     * Return all stops within a certain radius of the given vertex, using straight-line distance independent of streets.
     * If the origin vertex is a TransitStop, the result will include it.
     */
    /* Skip stops that are entrances to stations or whose entrances are coded separately */
    /* Determine the set of stops that are already reachable via other pathways or transfers */

}
