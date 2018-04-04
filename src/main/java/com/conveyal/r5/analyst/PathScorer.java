package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import org.apache.commons.math3.util.FastMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * For a given origin and destination, associates all the transit paths that are used in optimal paths with their total
 * end to end travel times, then scores and de-duplicates those paths to get the N most representative ones, considering
 * the percentile of travel time that will be reported.
 */
public class PathScorer {

    /**
     * All non-null paths to a particular destination, decorated with end-to-end travel times.
     */
    List<PathScore> pathScores;

    /**
     * Constructor. Makes protective copies of the supplied travel times and associates them with paths.
     * The paths are not scored at this point because we don't have a target travel time of interest yet.
     */
    public PathScorer(Path[] paths, int[] fullTravelTimes) {
        if (paths.length != fullTravelTimes.length) {
            throw new AssertionError("Parameters must be parallel arrays of equal length.");
        }
        this.pathScores = new ArrayList<>(paths.length);
        for (int p = 0; p < paths.length; p++) {
            // Record a path for scoring only if there's actually a transit trip (if the path is non-null).
            // This avoids scoring and sorting thousands of paths at destinations that are not reachable by transit.
            // TODO optimization: skip this iteration entirely where array itself is null (requires changes elsewhere).
            if (paths[p] != null && fullTravelTimes[p] != FastRaptorWorker.UNREACHED) {
                pathScores.add(new PathScore(paths[p], fullTravelTimes[p]));
            }
        }
    }

    /**
     * Decorates a path with characteristics that will allow us to score it later once we know the travel time of
     * interest.
     */
    private static class PathScore implements Comparable<PathScore> {
        Path path;
        int totalTravelTime;
        int score;

        public PathScore(Path path, int totalTravelTime) {
            this.path = path;
            this.totalTravelTime = totalTravelTime;
        }

        /**
         * This method is a bit of a hack to allow scoring the paths after the scorer instance is already created.
         * Each path gets a score (higher is worse) that is used to sort paths according to how well they represent
         * the target percentile of travel time.
         */
        public void computeScore (int targetTravelTimeSeconds) {
            int distanceFromTarget = FastMath.abs(targetTravelTimeSeconds - this.totalTravelTime);
            int nTransfers = this.path.length - 1;
            this.score = nTransfers * 5 * 60 + distanceFromTarget;
        }

        /**
         * Causes the instances to be sorted in order of increasing scores.
         */
        @Override
        public int compareTo(PathScore other) {
            return this.score - other.score;
        }
    }

    /**
     * Get the N most representative paths to this destination, considering the travel time that will be reported.
     * @param nPaths the maximum number of paths to return
     * @param targetTravelTimeSeconds the travel time that will be reported for this destination.
     * @return a collection of paths with nPaths or less elements. Never null, may be empty.
     */
    public Set<Path> getTopPaths (int nPaths, int targetTravelTimeSeconds) {
        // If the destination is unreachable at the selected percentile, don't report any paths.
        if (targetTravelTimeSeconds == FastRaptorWorker.UNREACHED){
            return Collections.EMPTY_SET;
        }

        // First, pre-score all the paths.
        // Anecdotally this is significantly faster than scoring them on the fly in a comparator method.
        for (PathScore pathScore : pathScores) {
            pathScore.computeScore(targetTravelTimeSeconds);
        }

        // Sort paths on scores without using generic Comparators (to avoid creation of boxed Integer objects).
        Collections.sort(pathScores);

        // This will hold the set of unique selected results.
        Set<Path> chosenPaths = new HashSet<>();

        // Iterate over the top ranked paths, de-duplicating them and skipping over any nulls.
        for (PathScore pathScore : pathScores) {
            Path path = pathScore.path;
            if (path != null) {
                chosenPaths.add(path);
                if (chosenPaths.size() == nPaths) {
                    break;
                }
            }
        }

        return chosenPaths;
    }

}
