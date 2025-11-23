package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Transfer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig;
import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.*;
import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * Transfers between transit stops can come from two sources: transfers.txt in GTFS inputs and
 * routing through the OSM-derived street network. This class handles loading from transfers.txt and
 * retains enough information so the subsequent street routing approach can adopt the behavior
 * specified with TransportNetworkConfig.TransferConfig. The way in which GTFS transfers take
 * priority over transfers found through the street network can be configured.
 * <p>
 * There can be multiple GTFS feeds and they are loaded in a streaming fashion, with only one open
 * and consuming memory at a time. Therefore we lose a lot of context by the time the street routing
 * happens.
 * <p>
 * Use a single instance across all GTFS feeds. Call the load method once on each feed in turn. This
 * accumulates information from all feeds that will later be important for finding transfers through
 * the OSM street network.
 * <p>
 * The GTFS transfers we load are specified in terms of minimum time, while the street transfers are
 * stored as distances and resolved to time during searches based on the specified walk speed. On
 * the downside this requires two separate data structures, but on the upside it simplifies loading
 * because GTFS must be loaded one feed at a time, while on-street transfers must be found later
 * after all GTFS feeds are loaded and all stops linked (because street transfers are expected to
 * connect stops from different feeds).
 * <p>
 * TODO Further increase transfer time accuracy using GTFS pathways.txt
 */
public class GtfsTransferLoader {

    final TransitLayer transit;
    final TransferConfig transferConfig;

    int nMissingStop = 0;
    int nUnsupportedSpecificity = 0;
    int nUnsupportedTransferType = 0;
    TObjectIntMap<String> otherErrors = new TObjectIntHashMap<>();

    public record StopPair(int fromStop, int toStop) { }

    final BitSet stopsWithTransfers = new BitSet();
    final Set<StopPair> stopPairsWithTransfers = new HashSet<>();
    // Transfer keys are from-stop indexes, values are packed lists of (to-stop, distance) pairs.
    final TIntObjectMap<TIntList> transfers = new TIntObjectHashMap<>();

    public GtfsTransferLoader (TransitLayer transit, TransferConfig transferConfig) {
        this.transit = transit;
        this.transferConfig = transferConfig;
    }

    public void loadTransfersTxt (GTFSFeed feed) {
        if (transferConfig == OSM_ONLY) return;
        if (feed.transfers == null || feed.transfers.isEmpty()) return;
        // The keys of GtfsFeed.transfers are just arbitrary unique numbers (the input line numbers).
        for (Transfer transfer : feed.transfers.values()) {
            if (shouldSkipTransfer(transfer)) continue;
            int from = transit.indexForStopId.get(transfer.from_stop_id);
            int to = transit.indexForStopId.get(transfer.to_stop_id);
            if (untrue(from < 0 || to < 0, "Transfer references stop that was not loaded.")) continue;
            if (untrue(transfer.min_transfer_time < 0, "Negative transfer times not allowed.")) continue;
            if (untrue(transfer.min_transfer_time > 3600, "Transfer time suspiciously high.")) continue;
            TIntList packedTransfers = transfers.get(from);
            if (packedTransfers == null) {
                packedTransfers = new TIntArrayList();
                transfers.put(from, packedTransfers);
            }
            packedTransfers.add(to);
            packedTransfers.add(transfer.min_transfer_time);
            stopsWithTransfers.set(from);
            stopsWithTransfers.set(to);
            // Conditional to avoid excessive object instance bloat when unused.
            if (transferConfig == PER_STOP_PAIR) {
                stopPairsWithTransfers.add(new StopPair(from, to));
            }
        }
        if (transferConfig == PER_FEED) {
            throw new UnsupportedOperationException();
            // Prevent later on-street transfer calculation for every stop in this feed.
            // However, this behavior probably needs to be different for inter- and intra-feed transfers.
            // And it may need to be manually set independently for each individual feed.
            // for (String stopId : feed.stops.keySet()) {
            //     int stopIndex = transit.indexForStopId.get(stopId);
            //     if (stopIndex > 0) stopsWithTransfers.set(stopIndex);
            // }
        }
    }

    // TODO encapsulate and reuse elsewhere.
    private boolean untrue (boolean condition, String errorMessage) {
        if (condition) otherErrors.adjustOrPutValue(errorMessage, 1, 1);
        return !condition;
    }

    /**
     * Validate one transfer and decide whether it should be processed by this class, maintaining
     * some counts and flags for debugging and status reporting.
     */
    private boolean shouldSkipTransfer (Transfer transfer) {
        boolean skip = false;
        if (!(isNullOrEmpty(transfer.from_route_id) && isNullOrEmpty(transfer.from_trip_id) &&
              isNullOrEmpty(transfer.to_route_id) && isNullOrEmpty(transfer.to_trip_id))) {
            nUnsupportedSpecificity += 1;
            skip = true;
        }
        if (isNullOrEmpty(transfer.from_stop_id) || isNullOrEmpty(transfer.to_stop_id)) {
            nMissingStop += 1;
            skip = true;
        }
        if (transfer.transfer_type < 2) {
            nUnsupportedTransferType += 1;
            skip = true;
        }
        if (transfer.transfer_type > 3) {
            // In-seat transfer information may be "supported", but not consumed by this class.
            skip = true;
        }
        return skip;
    }

    /**
     * This is an optimization to avoid a slow street search when every transfer it yields will be ignored (because
     * every stop pair involving this source stop is slated to be skipped).
     * @return whether the osm street transfer generation should skip producing any transfers from the given stop.
     */
    public boolean shouldSkipFromStop (int fromStopIndex) {
        if (transferConfig == PER_STOP) {
            return stopsWithTransfers.get(fromStopIndex);
        }
        return false;
    }

    /**
     * A GTFS transfer between a specific pair of stops or involving particular stops may take priority over any
     * transfer found by routing through the OSM street network.
     * @return whether osm street transfer generation should skip making transfers between the given pair of stops.
     */
    public boolean shouldSkipStopPair (int fromStopIndex, int toStopIndex) {
        if (transferConfig == PER_STOP_PAIR) {
            return stopPairsWithTransfers.contains(new StopPair(fromStopIndex, toStopIndex));
        } else if (transferConfig == PER_STOP) {
            return stopsWithTransfers.get(fromStopIndex) || stopsWithTransfers.get(toStopIndex);
        }
        return false;
    }

}
