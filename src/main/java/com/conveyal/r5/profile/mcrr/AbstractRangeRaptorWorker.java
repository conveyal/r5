package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.mcrr.api.DurationToStop;
import com.conveyal.r5.profile.mcrr.api.RangeRaptorRequest;
import com.conveyal.r5.profile.mcrr.api.TransitDataProvider;
import com.conveyal.r5.profile.mcrr.api.Worker;
import com.conveyal.r5.profile.mcrr.util.AvgTimer;
import com.conveyal.r5.profile.mcrr.util.TimeUtils;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Collection;


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
public abstract class AbstractRangeRaptorWorker<S extends WorkerState, P> implements Worker<P> {

    /** the transit data role needed for routing */
    protected final TransitDataProvider transit;

    // TODO add javadoc to field
    protected final S state;


    public AbstractRangeRaptorWorker(TransitDataProvider transitData, S state) {
        this.transit = transitData;
        this.state = state;
    }

    // Track time spent, measure performance
    // TODO TGR - Replace by performance tests
    protected abstract AvgTimer timerRoute();
    protected abstract void timerSetup(Runnable setup);
    protected abstract void timerRouteByMinute(Runnable routeByMinute);
    protected abstract AvgTimer timerByMinuteScheduleSearch();
    protected abstract AvgTimer timerByMinuteTransfers();

    protected abstract Collection<P> paths(Collection<DurationToStop> egressStops);

    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    protected abstract void addPathsForCurrentIteration(Collection<DurationToStop> egressStops);

    /**
     * Perform a scheduled search
     * @param boardSlackInSeconds {@link RangeRaptorRequest#boardSlackInSeconds}
     */
    protected abstract void scheduledSearchForRound(final int boardSlackInSeconds);


    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     *
     * @return a unique set of paths
     */
    public Collection<P> route(RangeRaptorRequest request) {
        //LOG.info("Performing {} rounds (minutes)",  nMinutes);

        timerRoute().time(() -> {
            timerSetup(transit::init);

            // The main outer loop iterates backward over all minutes in the departure times window.
            for (int departureTime = request.toTime - request.departureStepInSeconds;
                 departureTime >= request.fromTime;
                 departureTime -= request.departureStepInSeconds) {

                // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
                // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]
                final int dep = departureTime;
                timerRouteByMinute(() -> runRaptorForMinute(dep, request));
            }
        });
        return paths(request.egressStops);
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute(int nextMinuteDepartureTime, Collection<DurationToStop> accessStops) {
        state.initNewDepatureForMinute(nextMinuteDepartureTime);

        // add initial stops
        for (DurationToStop it : accessStops) {
            state.setInitialTime(it.stop, nextMinuteDepartureTime, it.time);
        }
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     */
    private void runRaptorForMinute(int departureTime, RangeRaptorRequest request) {
        state.debugStopHeader("RUN RAPTOR FOR MINUTE: " + TimeUtils.timeToStrCompact(departureTime));

        advanceScheduledSearchToPreviousMinute(departureTime, request.accessStops);

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
            timerByMinuteScheduleSearch().time(() -> scheduledSearchForRound(request.boardSlackInSeconds));

            timerByMinuteTransfers().time(this::doTransfers);
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here that creating these paths does not modify the state, and makes
        // protective copies of any information we want to retain.
        addPathsForCurrentIteration(request.egressStops);
    }


    private void doTransfers() {
        BitSetIterator it = state.stopsTouchedByTransitCurrentRound();

        for (int fromStop = it.next(); fromStop > -1; fromStop = it.next()) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            for (DurationToStop transfer : transit.getTransfers(fromStop)) {
                state.transferToStop(fromStop, transfer.stop, transfer.time);
            }
        }
    }

    /** Skip trips NOT running on the day of the search and skip frequency trips */
    protected boolean skipTripSchedule(TripSchedule trip) {
        return trip.headwaySeconds != null || transit.skipCalendarService(trip.serviceCode);
    }
}
