package com.conveyal.r5.transit;

import com.conveyal.r5.api.util.ParkRideParking;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
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
     * Should chooses whether to search via the street network or straight line distance based on the presence of
     * OSM street data (whether the street layer is null). However the street layer will always contain transit
     * stop vertices so not sure that can work.
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
            streetRouter.distanceLimitMeters = TransitLayer.PARKRIDE_DISTANCE_LIMIT;
            streetRouter.setOrigin(originStreetVertex);
            streetRouter.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;

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
        LOG.info("Found {} unconnected P+Rs and {} P+Rs without closest stop in {} m", unconnectedParkRides, parkRidesWithoutStops, TransitLayer.PARKRIDE_DISTANCE_LIMIT);
    }

    public void findTransfers () {
        int unconnectedStops = 0;
        // For each stop, store all transfers out of that stop as packed pairs of (toStopIndex, distance)
        final List<TIntList> transfersForStop = transitLayer.transfersForStop;
        // When applying scenarios we want to find transfers for only the newly added stops.
        // We look at any existing list of transfers and do enough iterations to make it as long as the list of stops.
        int firstStopIndex = transfersForStop.size();
        LOG.info("Finding transfers through the street network from {} stops...", transitLayer.getStopCount() - transfersForStop.size());
        // TODO Parallelize with streams. See distance table generation.
        for (int s = firstStopIndex; s < transitLayer.getStopCount(); s++) {
            // From each stop, run a street search looking for other transit stops.
            int originStreetVertex = transitLayer.streetVertexForStop.get(s);
            if (originStreetVertex == -1) {
                unconnectedStops++;
                // Every iteration must add an array to transfersForStop to maintain the right length.
                transfersForStop.add(EMPTY_INT_LIST);
                continue;
            }

            StreetRouter streetRouter = new StreetRouter(streetLayer);
            streetRouter.distanceLimitMeters = TransitLayer.TRANSFER_DISTANCE_LIMIT;

            streetRouter.setOrigin(originStreetVertex);
            streetRouter.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;

            streetRouter.route();
            TIntIntMap distancesToReachedStops = streetRouter.getReachedStops();
            // FIXME the following is technically incorrect, measure that it's actually improving calculation speed
            retainClosestStopsOnPatterns(distancesToReachedStops);
            // At this point we have the distances to all stops that are the closest one on some pattern.
            // Make transfers to them, packed as pairs of (target stop index, distance).
            TIntList packedTransfers = new TIntArrayList();
            distancesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
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
            // This is technically slightly incorrect, as distance(a, b) != distance(b, a), but for walking the equality
            // is close to holding.
            if (firstStopIndex > 0) {
                final int originStopIndex = s; // Why oh why, Java?
                // don't build transfers to other new stops
                distancesToReachedStops.forEachEntry((targetStopIndex, distance) -> {
                    if (targetStopIndex < firstStopIndex) {
                        TIntList packedTransfersCopy = new TIntArrayList(transfersForStop.get(targetStopIndex));
                        packedTransfersCopy.add(originStopIndex);
                        packedTransfersCopy.add(distance);
                        transfersForStop.set(targetStopIndex, packedTransfersCopy);
                    }
                    return true;
                });
            }

        }
        // Store the transfers in the transit layer
        transitLayer.transfersForStop = transfersForStop;
        LOG.info("Done finding transfers. {} stops are unlinked.", unconnectedStops);
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
