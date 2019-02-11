package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetWithMarker;

/**
 * A pareto optimal set of stop arrivals for a given stop.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StopArrivals<T extends TripScheduleInfo> extends ParetoSetWithMarker<AbstractStopArrival<T>> {
    private final DebugHandler<StopArrivalView<T>> debugHandler;

    StopArrivals(int stop, final DebugHandler<StopArrivalView<T>> debugHandler) {
        super(
                AbstractStopArrival.compareArrivalTimeRoundAndCost(),
                debugHandler::drop
        );
        this.debugHandler = debugHandler.isDebug(stop) ? debugHandler : null;
    }

    @Override
    public boolean add(AbstractStopArrival<T> newValue) {
        boolean added = super.add(newValue);
        debugAddNewValue(newValue, added);
        return added;
    }

    private void debugAddNewValue(AbstractStopArrival<T> newValue, boolean added) {
        if(debugHandler != null) {
            if(added ) {
                debugHandler.accept(newValue, this);
            }
            else {
                debugHandler.reject(newValue, this);
            }
        }
    }
}
