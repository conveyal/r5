package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Transfer;
import com.conveyal.r5.util.TIntIntHashSetMultimap;
import gnu.trove.TIntCollection;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;

import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig;
import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.OSM_ONLY;
import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.STOP_PAIR;
import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.STOP_TO_PATTERN;
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

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // TransitLayer is written to as output. Specifically, only the transit.gtfsTransfers map.
    final TransitLayer transit;
    // The way in which GTFS transfers override creation of OSM street transfers.
    final TransferConfig transferConfig;

    // These fields track different errors that can occur during transfer loading. This allows
    // error recovery and more complete error reports rather than bailing on the first error.
    int nMissingStop = 0;
    int nUnsupportedSpecificity = 0;
    int nUnsupportedTransferType = 0;
    TObjectIntMap<String> otherErrors = new TObjectIntHashMap<>();

    /// May return null or empty collection.
    public TIntCollection patternsToSkipForSourceStop (int sourceStopIndex) {
        if (transferConfig == STOP_TO_PATTERN) {
            return stopAndPatternPairsWithTransfers.get(sourceStopIndex);
        }
        return null;
    }

    // These fields track which transfers have been loaded to supersede OSM-based transfers.
    public record StopPair(int fromStop, int toStop) { }
    final Set<StopPair> stopPairsWithTransfers = new HashSet<>();
    final TIntIntHashSetMultimap stopAndPatternPairsWithTransfers = new TIntIntHashSetMultimap();

    public GtfsTransferLoader (TransitLayer transit, TransferConfig transferConfig) {
        this.transit = transit;
        this.transferConfig = transferConfig != null ? transferConfig : STOP_TO_PATTERN;
    }

    ///  @param indexForUnscopedStopId Map of stop IDs not yet scoped by feed ID, which exists only during GTFS loading
    public void loadTransfersTxt (GTFSFeed feed, TObjectIntMap<String> indexForUnscopedStopId) {
        if (transferConfig == OSM_ONLY) return;
        if (feed.transfers == null || feed.transfers.isEmpty()) return;
        LOG.info("GTFS {} contains transfers. Loading them in mode {}.", feed.feedId, transferConfig);
        // The keys of GtfsFeed.transfers are just arbitrary unique numbers (the input line numbers).
        for (Transfer transfer : feed.transfers.values()) {
            if (shouldSkipTransfer(transfer)) continue;
            int from = indexForUnscopedStopId.get(transfer.from_stop_id);
            int to = indexForUnscopedStopId.get(transfer.to_stop_id);
            if (untrue(from < 0 || to < 0, "Transfer references stop that was not loaded.")) continue;
            if (untrue(transfer.min_transfer_time < 0, "Negative transfer times not allowed.")) continue;
            if (untrue(transfer.min_transfer_time > 3600, "Transfer time suspiciously high.")) continue;
            TIntList packedTransfers = transit.gtfsTransfers.get(from);
            if (packedTransfers == null) {
                packedTransfers = new TIntArrayList();
                transit.gtfsTransfers.put(from, packedTransfers);
            }
            packedTransfers.add(to);
            packedTransfers.add(transfer.min_transfer_time);
            // Conditional to avoid excessive object instance bloat when unused.
            if (transferConfig == STOP_PAIR) {
                stopPairsWithTransfers.add(new StopPair(from, to));
            } else if (transferConfig == STOP_TO_PATTERN) {
                TIntList patterns = transit.patternsForStop.get(from);
                stopAndPatternPairsWithTransfers.putAll(from, patterns);
            }
        }
    }

    private boolean untrue (boolean condition, String errorMessage) {
        if (condition) otherErrors.adjustOrPutValue(errorMessage, 1, 1);
        return !condition;
    }

    /// Validate one GTFS transfer and decide whether it should be processed by this class,
    /// maintaining some counts and flags for debugging and status reporting.
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

    /// A GTFS transfer between a specific pair of stops or involving particular stops may take
    /// priority over any transfer found by routing through the OSM street network.
    /// @return whether osm street transfer generation should skip making transfers between the given pair of stops.
    public boolean shouldSkipStopPair (int fromStopIndex, int toStopIndex) {
        if (transferConfig == STOP_PAIR) {
            return stopPairsWithTransfers.contains(new StopPair(fromStopIndex, toStopIndex));
        }
        return false;
    }

}
