package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.standard.WorkerState;
import com.conveyal.r5.profile.entur.api.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.Worker;
import com.conveyal.r5.profile.entur.util.AvgTimer;
import com.conveyal.r5.profile.entur.util.BitSetIterator;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.Collection;
import java.util.Iterator;


/**
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
 * the travel times from the final transit stop of a trip out to the individual targets.
 * <p>
 * The algorithm used herein is described in
 * <p>
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 * Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 * Record 2653 (2017). doi:10.3141/2653-06.
 * <p>
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 * http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 * <p>
 * There is currently no support for saving paths.
 * <p>
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 */
@SuppressWarnings("Duplicates")
public abstract class AbstractRangeRaptorWorker<S extends WorkerState> implements Worker {

    /** the transit data role needed for routing */
    protected final TransitDataProvider transit;

    // TODO add javadoc to field
    protected final S state;

    // TODO add javadoc to field
    protected final RangeRaptorRequest request;

    public AbstractRangeRaptorWorker(TransitDataProvider transitData, S state, RangeRaptorRequest request) {
        this.transit = transitData;
        this.state = state;
        this.request = request;
    }

    // Track time spent, measure performance
    // TODO TGR - Replace by performance tests
    protected abstract AvgTimer timerRoute();
    protected abstract void timerSetup(Runnable setup);
    protected abstract void timerRouteByMinute(Runnable routeByMinute);
    protected abstract AvgTimer timerByMinuteScheduleSearch();
    protected abstract AvgTimer timerByMinuteTransfers();

    protected abstract Collection<Path2> paths();

    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    protected abstract void addPathsForCurrentIteration();

    /**
     * Perform a scheduled search
     */
    protected abstract void scheduledSearchForRound();


    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     *
     * @return a unique set of paths
     */
    public Collection<Path2> route() {
        timerRoute().time(() -> {
            timerSetup(transit::init);

            // The main outer loop iterates backward over all minutes in the departure times window.
            for (int departureTime = request.toTime - request.departureStepInSeconds;
                 departureTime >= request.fromTime;
                 departureTime -= request.departureStepInSeconds) {

                // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
                // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]
                final int dep = departureTime;
                timerRouteByMinute(() -> runRaptorForMinute(dep));
            }
        });
        return paths();
    }


    protected final int earliestBoardTime(int time) {
        return time + request.boardSlackInSeconds;
    }


    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     */
    private void runRaptorForMinute(int departureTime) {
        state.debugStopHeader("RUN RAPTOR FOR MINUTE: " + TimeUtils.timeToStrCompact(departureTime));

        advanceScheduledSearchToPreviousMinute(departureTime);

        // Run the scheduled search
        // round 0 is the street search
        // We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
        // “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
        // ergo, we re-use the arrival times found in searches that have already occurred that depart later, because
        // the arrival time given departure at time t is upper-bounded by the arrival time given departure at minute t + 1.

        while (state.isNewRoundAvailable()) {
            state.gotoNextRound();

            // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
            // as that will be rare and complicates the code grabbing the results
            timerByMinuteScheduleSearch().time(this::scheduledSearchForRound);

            timerByMinuteTransfers().time(this::doTransfers);
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here that creating these paths does not modify the state, and makes
        // protective copies of any information we want to retain.
        addPathsForCurrentIteration();
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute(
            int nextMinuteDepartureTime
    ) {
        state.initNewDepatureForMinute(nextMinuteDepartureTime);

        // add initial stops
        for (StopArrival it : request.accessStops) {
            state.setInitialTime(it, nextMinuteDepartureTime, request.boardSlackInSeconds);
        }
    }


    private void doTransfers() {
        BitSetIterator it = state.stopsTouchedByTransitCurrentRound();

        for (int fromStop = it.next(); fromStop > -1; fromStop = it.next()) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            Iterator<StopArrival> transfers = transit.getTransfers(fromStop);
            while (transfers.hasNext()) {
                StopArrival transfer = transfers.next();
                state.transferToStop(fromStop, transfer);
            }
        }
    }

    /** Skip trips NOT running on the day of the search and skip frequency trips */
    protected boolean skipTripSchedule(TripScheduleInfo trip) {
        return !transit.isTripScheduleInService(trip);
    }
}
