package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.entur.util.ParetoDominanceFunctions;
import com.conveyal.r5.profile.entur.util.ParetoSortable;

import static com.conveyal.r5.profile.entur.util.ParetoDominanceFunctions.createParetoDominanceFunctionArray;

@Deprecated
public class PathParetoSortableWrapper implements ParetoSortable {

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

    @Override
    public int paretoValue1() {
        return patternsHash;
    }
    @Override
    public int paretoValue2() {
        return boardTime;
    }
    @Override
    public int paretoValue3() {
        return totalJourneyDuration;
    }

    public static ParetoDominanceFunctions.Builder paretoDominanceFunctions() {
        return createParetoDominanceFunctionArray()
                .different()
                .different()
                .lessThen();
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
