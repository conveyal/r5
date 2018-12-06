package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetWithMarker;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class Stop<T extends TripScheduleInfo> extends ParetoSetWithMarker<AbstractStopArrival<T>> {
    private final DebugHandler<StopArrivalView<T>> debugHandler;
    private boolean debug;

    Stop(int stop, final DebugHandler<StopArrivalView<T>> debugHandler) {
        super(
                AbstractStopArrival.compareArrivalTimeRoundAndCost(),
                debugHandler::drop
        );
        this.debugHandler = debugHandler;
        this.debug = debugHandler.isDebug(stop);
    }

    @Override
    public boolean add(AbstractStopArrival<T> newValue) {
        boolean added = super.add(newValue);

        if(debug) {
            if(added ) {
                debugHandler.accept(newValue, this);
            }
            else {
                debugHandler.reject(newValue, this);
            }
        }
        return added;
    }
}
