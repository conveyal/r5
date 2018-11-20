package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparatorBuilder;
import com.conveyal.r5.transit.TripSchedule;

@Deprecated
public class Path2ParetoSortableWrapper  {

    public final Path2<TripSchedule> path;
    private final int arrivalTime;
    private final int journeyDuration;

    public Path2ParetoSortableWrapper(Path2<TripSchedule> path) {
        this.path = path;
        this.arrivalTime = path.egressLeg().toTime();
        this.journeyDuration = arrivalTime - path.accessLeg().fromTime();
    }

    public static ParetoComparator<Path2ParetoSortableWrapper> paretoDominanceFunctions() {
        return new ParetoComparatorBuilder<Path2ParetoSortableWrapper>()
                .lessThen(it -> it.arrivalTime)
                .lessThen(it -> it.journeyDuration)
                .build();
    }
}
