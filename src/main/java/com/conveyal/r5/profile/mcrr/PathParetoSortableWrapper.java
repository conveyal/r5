package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.util.ParetoDominateFunction;
import com.conveyal.r5.util.ParetoSortable;


import static com.conveyal.r5.util.ParetoDominateFunction.createParetoDominanceFunctionArray;

public class PathParetoSortableWrapper implements ParetoSortable {

    public final Path path;
    private final int[] paretoValues = new int[3];

    public PathParetoSortableWrapper(Path path, int totalJourneyDuration) {
        this.path = path;
        // We uses a hash(), but this may lead to collision and lost paths
        paretoValues[0] = hash(path.patterns);
        paretoValues[1] = path.boardTimes[0];
        paretoValues[2] = totalJourneyDuration;
    }

    @Override
    public int[] paretoValues() {
        return paretoValues;
    }

    public static ParetoDominateFunction.Builder paretoDominanceFunctions() {
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
