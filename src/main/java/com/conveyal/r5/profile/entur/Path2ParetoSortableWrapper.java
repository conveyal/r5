package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSortable;

import static com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction.createParetoFunctions;

@Deprecated
public class Path2ParetoSortableWrapper implements ParetoSortable {

    public final Path2 path;
    private final int arrivalTime;
    private final int journeyDuration;

    public Path2ParetoSortableWrapper(Path2 path) {
        this.path = path;
        this.arrivalTime = path.egressLeg().toTime();
        this.journeyDuration = arrivalTime - path.accessLeg().fromTime();
    }

    @Override public int paretoValue1() { return arrivalTime; }
    @Override public int paretoValue2() { return journeyDuration; }

    public static ParetoFunction[] paretoDominanceFunctions() {
        return createParetoFunctions()
                .lessThen()
                .lessThen()
                .build();
    }
}
