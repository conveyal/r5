package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparatorBuilder;

@Deprecated
public class PathParetoSortableWrapper {

    public final Path path;
    private final int patternsHash;
    private final int boardTime;
    private final int totalJourneyDuration;

    public PathParetoSortableWrapper(Path path, int totalJourneyDuration) {
        this.path = path;
        // We uses a hash(), but this may lead to collision and lost paths
        this.patternsHash = hash(path.patterns);
        this.boardTime = path.boardTimes[0];
        this.totalJourneyDuration = totalJourneyDuration;
    }

    public static ParetoComparator<PathParetoSortableWrapper> paretoComparator() {
        return new ParetoComparatorBuilder<PathParetoSortableWrapper>()
                .different(it -> it.patternsHash)
                .different(it -> it.boardTime)
                .lessThen(it -> it.totalJourneyDuration)
                .build();
    }


    /* private methods */

    /**
     * As all other hash functions this function may lead to collision, but only if the vector passed inn have
     * the same length for all length < 127.
     */
    private static int hash(int[] vector) {
        int hash = 0;
        for (int v : vector) {
            hash = (hash << 7) + v;
        }
        return (hash << 7) + vector.length;
    }
}
