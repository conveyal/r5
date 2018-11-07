package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import java.util.ArrayList;
import java.util.List;


final class McPath<T extends TripScheduleInfo> implements Path2<T> {
    private final PathLeg<T> accessLeg;
    private final List<PathLeg<T>> legs = new ArrayList<>();
    private final PathLeg<T> egressLeg;

    McPath(List<McStopState<T>> states, int egressTime) {
        this.accessLeg = McPathLeg.createAccessLeg((McAccessStopState<T>)states.get(0), states.get(1).boardTime());

        for (int i=1; i<states.size(); ++i) {
            this.legs.add(McPathLeg.createLeg(states.get(i)));
        }
        this.egressLeg = McPathLeg.createEgressLeg((McTransitStopState<T>) states.get(states.size()-1), egressTime);
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
