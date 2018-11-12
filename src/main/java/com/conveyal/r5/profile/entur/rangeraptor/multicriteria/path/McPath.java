package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;

import java.util.ArrayList;
import java.util.List;

import static com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path.McPathLeg.createAccessLeg;
import static com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path.McPathLeg.createEgressLeg;
import static com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path.McPathLeg.createLeg;


final class McPath<T extends TripScheduleInfo> implements Path2<T> {
    private final PathLeg<T> accessLeg;
    private final List<PathLeg<T>> legs = new ArrayList<>();
    private final PathLeg<T> egressLeg;

    McPath(DestinationArrival<T> egressLeg) {
        List<AbstractStopArrival<T>> states = egressLeg.getPreviousState().path();
        this.accessLeg = createAccessLeg((AccessStopArrival<T>)states.get(0), states.get(1).boardTime());

        for (int i=1; i<states.size(); ++i) {
            this.legs.add(createLeg(states.get(i)));
        }
        //noinspection unchecked
        this.egressLeg = createEgressLeg(egressLeg);
    }

    @Override
    public PathLeg<T> accessLeg() {
        return accessLeg;
    }

    @Override
    public Iterable<PathLeg<T>> legs() {
        return legs;
    }

    @Override
    public PathLeg<T> egressLeg() {
        return egressLeg;
    }
}
