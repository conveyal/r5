package com.conveyal.r5.transit;

import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.r5.streets.StreetRouter.State.RoutingVariable;
import static com.conveyal.r5.transit.TransitLayer.PARKRIDE_DISTANCE_LIMIT_METERS;
import static com.conveyal.r5.transit.TransitLayer.TRANSFER_DISTANCE_LIMIT_METERS;

/**
 * Pre-compute walking transfers between transit stops via the street network, up to a given distance limit.
 * TODO optimization: combine TransferFinder with stop-to-vertex distance table builder.
 */
public class TransferFinder {

    private static final Logger LOG = LoggerFactory.getLogger(TransferFinder.class);

    // Optimization: use the same empty list for all stops with no transfers
    private static final TIntArrayList EMPTY_INT_LIST = new TIntArrayList();

    // Optimization: use the same empty list for all stops with no transfers
    private static final TIntObjectMap<StreetRouter.State> EMPTY_STATE_MAP = new TIntObjectHashMap<>();

    TransitLayer transitLayer;

    StreetLayer streetLayer;

    /**
     * Eventually this should choose whether to search via the street network or straight line distance based on the
     * presence of OSM street data (whether the street layer is null). However the street layer will always be present,
     * at least to contain transit stop vertices, so the choice cannot be made based only on the absence of a streetLayer.
     */
    public TransferFinder(TransportNetwork network) {
        this.transitLayer = network.transitLayer;
        this.streetLayer = network.streetLayer;
    }

    public void findParkRideTransfer() {
        int unconnectedParkRides = 0;
        int parkRidesWithoutStops = 0;
        LOG.info("Finding closest stops to P+R for {} P+Rs", this.streetLayer.parkRideLocationsMap.size());
        for (ParkRideParking parkRideParking : this.streetLayer.parkRideLocationsMap.valueCollection()) {
            int originStreetVertex;
            if (parkRideParking.id == null || parkRideParking.id < 0) {
                unconnectedParkRides++;
                continue;
            } else {
                originStreetVertex = parkRideParking.id;
            }

            StreetRouter streetRouter = new StreetRouter(streetLayer);
            streetRouter.distanceLimitMeters = PARKRIDE_DISTANCE_LIMIT_METERS;
            streetRouter.setOrigin(originStreetVertex);
            streetRouter.quantityToMinimize = RoutingVariable.DISTANCE_MILLIMETERS;

            streetRouter.transitStopSearch = true;
            streetRouter.route();

            TIntIntMap distancesToReachedStops = streetRouter.getReachedStops();
            // FIXME the following is technically incorrect, measure that it's actually improving calculation speed
            retainClosestStopsOnPatterns(distancesToReachedStops);
            // At this point we have the distances to all stops that are the closest one on some pattern.
            // Make transfers to them, packed as pairs of (target stop index, distance).
            TIntObjectMap<StreetRouter.State> pathToreachedStops = new TIntObjectHashMap<>(distancesToReachedStops.size());
            distancesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
                int stopStreetVertexIdx = transitLayer.streetVertexForStop.get(targetStopIndex);
                StreetRouter.State path = streetRouter.getStateAtVertex(stopStreetVertexIdx);
                pathToreachedStops.put(targetStopIndex, path);
                return true;
            });

            // Record this list of transfers as leading out of the stop with index s.
            if (pathToreachedStops.size() > 0) {
                parkRideParking.closestTransfers = pathToreachedStops;
                LOG.debug("Found {} stops for P+R:{}", distancesToReachedStops.size(), parkRideParking.id);
            } else {
                parkRideParking.closestTransfers = EMPTY_STATE_MAP;
                LOG.debug("Not found stops for Park ride:{}", parkRideParking.id);
                parkRidesWithoutStops++;
            }
        }
        LOG.info("Found {} unconnected P+Rs and {} P+Rs without closest stop in {} m", unconnectedParkRides, parkRidesWithoutStops, PARKRIDE_DISTANCE_LIMIT_METERS);
    }

    /**
     * For each stop, store all transfers out of that stop as packed pairs of (toStopIndex, distance).
     * When applying scenarios, we want to find transfers for only the newly added stops, keeping the existing transfers.
     * However, existing transfer lists will be extended if new stops are reachable from existing stops.
     */
    public void findTransfers () {
        // Look at the existing list of transfers (if any) and do enough iterations to make that transfer list as long
        // as the list of stops.
        int firstStopToProcess = transitLayer.transfersForStop.size();
        int nStopsTotal = transitLayer.getStopCount();
        int nStopsToProcess =  nStopsTotal - firstStopToProcess;
        LOG.info("Finding transfers through the street network from {} stops...", nStopsToProcess);
        LambdaCounter stopCounter = new LambdaCounter(LOG, nStopsToProcess, 10_000,
                "Found transfers from {} of {} transit stops.");
        LambdaCounter unconnectedCounter = new LambdaCounter(LOG, nStopsToProcess, 1_000,
                "{} of {} transit stops are unlinked.");

        // Create transfers for all new stops, appending them to the list of transfers for any existing stops.
        // This handles both newly built networks and the case where a scenario adds stops to an existing network.
        transitLayer.transfersForStop.addAll(
                IntStream.range(firstStopToProcess, nStopsTotal).parallel().mapToObj(sourceStopIndex -> {
            stopCounter.increment();
            // From each stop, run a street search looking for other transit stops.
            int originStreetVertex = transitLayer.streetVertexForStop.get(sourceStopIndex);
            if (originStreetVertex == -1) {
                unconnectedCounter.increment();
                // We must add an array to transfersForStop for every source stop to maintain the right length.
                return EMPTY_INT_LIST;
            }

            StreetRouter streetRouter = new StreetRouter(streetLayer);
            streetRouter.distanceLimitMeters = TRANSFER_DISTANCE_LIMIT_METERS;

            streetRouter.setOrigin(originStreetVertex);
            streetRouter.quantityToMinimize = RoutingVariable.DISTANCE_MILLIMETERS;

            streetRouter.route();
            TIntIntMap distancesToReachedStops = streetRouter.getReachedStops();
            // TODO the following optimization is incorrect for some loop and U-shaped routes, measure that it's actually improving routing speed
            retainClosestStopsOnPatterns(distancesToReachedStops);
            // At this point we have the distances to all stops that are the closest one on some pattern.
            // Make transfers to them, packed as pairs of (target stop index, distance).
            TIntList packedTransfers = new TIntArrayList();
            distancesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
                packedTransfers.add(targetStopIndex);
                packedTransfers.add(distance);
                return true;
            });
            // Record this list of transfers as leading out of the stop with index sourceStopIndex.
            // Deduplicate empty lists.
            if (packedTransfers.size() > 0) {
                return packedTransfers;
            } else {
                return EMPTY_INT_LIST;
            }
        }).collect(Collectors.toList()));
        LOG.info("Done finding transfers. {} stops were not linked to the street network.", unconnectedCounter.getCount());

        // If we are applying a scenario (extending the transfers list rather than starting from scratch), for
        // all transfers out of a scenario stop into a base network stop we must also create the reverse transfer.
        // The original packed transfers list is copied on write to avoid perturbing the base network.
        // This is technically slightly incorrect, as distance(a, b) != distance(b, a), but for walking the equality
        // is close to holding. We do this by post-processing the list to allow parallel computation above. This
        // post-processing stage is much faster than performing the street searches and does not lend itself well to
        // a streaming approach.
        if (firstStopToProcess > 0) {
            LOG.info("Appending inverse transfers for scenario application...", nStopsToProcess);
            for (int sourceStopIndex = firstStopToProcess; sourceStopIndex < nStopsTotal; sourceStopIndex++) {
                TIntList distancesToTargetStops = transitLayer.transfersForStop.get(sourceStopIndex);
                for (int i = 0; i < distancesToTargetStops.size(); i += 2) {
                    int targetStopIndex = distancesToTargetStops.get(i);
                    int distance = distancesToTargetStops.get(i + 1);
                    // Only create inverted transfers when target is a pre-existing (non-scenario) stop
                    if (targetStopIndex < firstStopToProcess) {
                        TIntList packedTransfersCopy = new TIntArrayList(transitLayer.transfersForStop.get(targetStopIndex));
                        packedTransfersCopy.add(sourceStopIndex);
                        packedTransfersCopy.add(distance);
                        transitLayer.transfersForStop.set(targetStopIndex, packedTransfersCopy);
                    }
                }
            }
        }
    }


    /**
     * Filter down a map from target stop indexes to distances so it only includes those stops that are the
     * closest on some pattern. This is technically incorrect (think of transfers to a U shaped metro from a bus line
     * running across the legs of the U, a situation which actually exists in Washington, DC with the
     * red line and the Q4) but anecdotally it speeds up computation by up to 40 percent. We may want to look into
     * other ways to optimize transfers (or why the transfers are making routing so much slower) if this turns out to
     * affect results.
     */
    private void retainClosestStopsOnPatterns(TIntIntMap timesToReachedStops) {
        TIntIntMap bestStopOnPattern = new TIntIntHashMap(50, 0.5f, -1, -1);
        // For every reached stop,
        timesToReachedStops.forEachEntry((stopIndex, distanceToStop) -> {
            // For every pattern passing through that stop,
            transitLayer.patternsForStop.get(stopIndex).forEach(patternIndex -> {
                int currentBestStop = bestStopOnPattern.get(patternIndex);
                // Record this stop if it's the closest one yet seen on that pattern.
                if (currentBestStop == -1) {
                    bestStopOnPattern.put(patternIndex, stopIndex);
                } else {
                    int currentBestTime = timesToReachedStops.get(currentBestStop);
                    if (currentBestTime > distanceToStop) {
                        bestStopOnPattern.put(patternIndex, stopIndex);
                    }
                }
                return true; // iteration should continue
            });
            return true; // iteration should continue
        });
        timesToReachedStops.retainEntries((stop, distance) -> bestStopOnPattern.containsValue(stop));
    }

}
