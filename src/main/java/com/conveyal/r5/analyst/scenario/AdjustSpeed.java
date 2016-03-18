package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scale travel speed by a constant factor. That is, uniformly speed trips up or slow them down.
 * This modification can also be applied to only part of a route by specifying a series of "hops", i.e.
 * pairs of stops adjacent to one another in the pattern.
 * We do not have an absolute speed parameter, only a scale parameter, because the server does not necessarily know
 * the route alignment and the inter-stop distances.
 */
public class AdjustSpeed extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(AdjustSpeed.class);

    /** The routes which should be sped up or slowed down. */
    public Set<String> routes;

    /** Trips which should be sped up or slowed down. */
    public Set<String> trips;

    /** The multiplicative scale factor for speeds. */
    public double scale = -1;

    /** Which stops in the route serve as the fixed points around which trips are contracted or expanded. */
    public List<String> referenceStops;

    /**
     * Hops which should have their speed set or scaled. If not supplied, all hops should be modified.
     * Each hop is a pair of adjacent stop IDs (from/to) and the hop specification is directional.
     */
    public List<StopPair> hops;

    /**
     * If true, the scale factor applies to both dwells and inter-stop rides. If false, dwells remain the
     * same length and the scale factor only applies to rides.
     */
    public boolean scaleDwells = false;

    /** This will be set to true if any errors occur while resolving String-based IDs against the network. */
    private boolean errorsResolving = false;

    /** This will be set to true if any errors occur while applying the modification to a network. */
    private boolean errorsApplying = false;

    private TIntList hopFromStops;

    private TIntList hopToStops;

    @Override
    public String getType() {
        return "adjust-speed";
    }

    @Override
    public boolean resolve(TransportNetwork network) {
        if (scale <= 0) {
            warnings.add("Scaling factor must be a positive number.");
            errorsResolving = true;
        }
        hopFromStops = new TIntArrayList(hops.size());
        hopToStops = new TIntArrayList(hops.size());
        for (StopPair pair: hops) {
            int intFromId = network.transitLayer.indexForStopId.get(pair.fromStop);
            int intToId = network.transitLayer.indexForStopId.get(pair.toStop);
            if (intFromId == 0 || intToId == 0) { // FIXME should be -1 not 0
                warnings.add("Could not find stop for ID in " + pair);
                errorsResolving = true;
            } else {
                hopFromStops.add(intFromId);
                hopToStops.add(intToId);
            }
        }
        return errorsResolving;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(tp -> this.applyToTripPattern(tp))
                .collect(Collectors.toList());
        return errorsApplying;
    }

    private TripPattern applyToTripPattern (TripPattern originalTripPattern) {
        if (!routes.contains(originalTripPattern.routeId)) {
            // TODO handle scaling individual trips rather than whole routes... but do we even need that?
            return originalTripPattern;
        }
        // TODO Decide which hops in this pattern should be affected.
        TripPattern pattern = originalTripPattern.clone();
        pattern.tripSchedules = pattern.tripSchedules.stream()
                .map(schedule -> this.applyToTripSchedule(pattern, schedule))
                .collect(Collectors.toList());
        LOG.debug("Scaled speeds (factor {}) for all trips on {}.", scale, originalTripPattern);
        return pattern;
    }

    private TripSchedule applyToTripSchedule (TripPattern pattern, TripSchedule originalSchedule) {
        double timeScaleFactor = 1/scale; // Invert speed factor to get time factor
        int nStops = originalSchedule.getNStops();
        TripSchedule newSchedule = originalSchedule.clone();
        newSchedule.arrivals = new int[nStops];
        newSchedule.departures = new int[nStops];
        // Use a floating-point number to avoid accumulating integer truncation error.
        double seconds = originalSchedule.arrivals[0];
        for (int s = 0; s < nStops; s++) {
            int dwellTime = originalSchedule.departures[s] - originalSchedule.arrivals[s];
            newSchedule.arrivals[s] = (int) Math.round(seconds);
            if (scaleDwells) {
                seconds += dwellTime * timeScaleFactor;
            } else {
                seconds += dwellTime;
            }
            newSchedule.departures[s] = (int) Math.round(seconds);
            if (s >= nStops - 1) {
                // We are not at the last stop in the pattern, so compute and optionally scale the following hop.
                int rideTime = originalSchedule.arrivals[s + 1] - originalSchedule.departures[s];
                boolean scaleThisHop = false;
                // TODO this is repetitive, factor this out to the pattern level.
                for (int i = 0; i < hopFromStops.size(); i++) {
                    if (hopFromStops.get(i) == pattern.stops[s] && hopToStops.get(i) == pattern.stops[s+1]) {
                        scaleThisHop = true;
                        break;
                    }
                }
                if (scaleThisHop) {
                    seconds += rideTime * timeScaleFactor;
                } else {
                    seconds += rideTime;
                }
            }
        }
        int originalTravelTime = originalSchedule.departures[nStops - 1] - originalSchedule.arrivals[0];
        int updatedTravelTime = newSchedule.departures[nStops - 1] - newSchedule.arrivals[0];
        LOG.debug("Total travel time on trip {} changed from {} to {} seconds.",
                newSchedule.tripId, originalTravelTime, updatedTravelTime);
        postSanityCheck(newSchedule);
        return newSchedule;
    }

    private static void postSanityCheck (TripSchedule schedule) {
        // TODO check that modified trips still make sense after applying the modification
    }

}
