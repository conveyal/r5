package com.conveyal.r5.profile.entur.api;


/**
 * Tuning parameters - changing these parameters change the performance (speed and/or memory consumption).
 */
public interface TuningParameters {

    /**
     * This parameter is used to allocate enough memory space for Raptor.
     * Set it to the maximum number of transfers for any given itinerary expected to
     * be found within the entire transit network.
     * <p/>
     * Default value is 12.
     */
    default int maxNumberOfTransfers() {
        return 12;
    }

    /**
     * This threshold is used to determine when to perform a binary trip schedule search
     * to reduce the number of trips departure time lookups and comparisons. When testing
     * with data from Entur and all of Norway as a Graph, the optimal value was about 50.
     * <p/>
     * If you calculate the departure time every time or want to fine tune the performance,
     * changing this may improve the performance a few percent.
     * <p/>
     * Default value is 50.
     */
    default int scheduledTripBinarySearchThreshold() {
        return 50;
    }

    /**
     * Step for departure times between each RangeRaptor iterations.
     * This is a performance optimization parameter.
     * A transit network usually uses minute resolution for the its timetable,
     * so to match that set this variable to 60 seconds. Setting it
     * to less then 60 will not give better result, but degrade performance.
     * Setting it to 120 seconds will improve performance, but you might get a
     * slack of 60 seconds somewhere in the result - most likely in the first
     * walking leg.
     * <p/>
     * Default value is 60.
     */
    default int iterationDepartureStepInSeconds() {
        return 60;
    }

    /**
     * This accept none optimal trips if they are close enough - if and only if they represent an optimal path
     * for their given iteration. I other words this slack only relax the pareto comparison at the destination.
     * <p/>
     * Let {@code c} be the existing minimum pareto optimal cost to to beat. Then a trip with cost {@code c'} is accepted if the
     * following is true:
     * <pre>
     * c' <= Math.round(c * relaxCostAtDestinationArrival)
     * </pre>
     * TODO - When setting this above 1.0, we get some unwanted results. We should have a filter to remove those
     * TODO - results. See issue https://github.com/entur/r5/issues/28
     * <p/>
     * The default value is 1.0
     */
    default double relaxCostAtDestinationArrival() {
        return 1.0;
    }
}
