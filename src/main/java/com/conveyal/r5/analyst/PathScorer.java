package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.transit.path.Path;
import com.conveyal.r5.transit.path.PatternSequence;
import org.apache.commons.math3.util.FastMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * For a given origin and destination, associates all the transit paths that are used in optimal paths with their total
 * end to end travel times, then scores and de-duplicates those paths to get the N most representative ones, considering
 * the percentile of travel time that will be reported. This is used to select paths to include in Taui output. It
 * should eventually be combined with a mechanism for reporting paths yielding selected percentiles of travel time in
 * single point and regional requests.
 */
public class PathScorer {

    /**
     * All non-null paths to a particular destination, decorated with end-to-end travel times.
     */
    List<PathScore> pathScores;

    /**
     * Constructor. Makes protective copies of the supplied travel times and associates them with paths.
     * The paths are not scored at this point because we don't have a target travel time of interest yet.
     * We reduce the Paths to their PatternSequence, which represents the level of deduplication we want
     * in the Taui files. PatternSequence also has semantic equals/hashcode, which Path does not currently have.
     */
    public PathScorer (int[] fullTravelTimes, Path[] paths, StreetTimesAndModes.StreetTimeAndMode[] perIterationEgress) {
        if (paths.length != fullTravelTimes.length || paths.length != perIterationEgress.length) {
            throw new AssertionError("Parameters must be parallel arrays of equal length.");
        }
        this.pathScores = new ArrayList<>(paths.length);
        for (int p = 0; p < paths.length; p++) {
            // Record a path for scoring only if there's actually a transit trip (if the path is non-null).
            // This avoids scoring and sorting thousands of paths at destinations that are not reachable by transit.
            // TODO optimization: skip this iteration entirely where array itself is null (requires changes elsewhere).
            if (paths[p] != null && fullTravelTimes[p] != FastRaptorWorker.UNREACHED) {
                PatternSequence pseq = new PatternSequence(paths[p].patternSequence, perIterationEgress[p]);
                pathScores.add(new PathScore(pseq, fullTravelTimes[p]));
            }
        }
    }

    /**
     * Decorates a PatternSequence with characteristics that will allow us to score it later once we know the travel
     * time for which we want to selet representative paths. We might not really need to perform this wrapping anymore
     * to attach the totalTravelTime to PatternSequence, since Path now represents that full end to end travel time.
     * But this is an adaptation of existing Taui writing classes, and we want to achieve a certain deduplication
     * behavior at the PatternSequence level.
     */
    private static class PathScore implements Comparable<PathScore> {

        PatternSequence patternSequence;
        int totalTravelTime;
        int score;

        public PathScore(PatternSequence patternSequence, int totalTravelTime) {
            this.patternSequence = patternSequence;
            this.totalTravelTime = totalTravelTime;
        }

        /**
         * This method is a bit of a hack to allow scoring the paths after the scorer instance is already created.
         * Each path gets a score (higher is worse) that is used to sort paths according to how well they represent
         * the target percentile of travel time.
         */
        public void computeScore (int targetTravelTimeSeconds) {
            int distanceFromTarget = FastMath.abs(targetTravelTimeSeconds - this.totalTravelTime);
            // TODO any chance this can end up negative? How are we handling the case where there's no transit?
            int nTransfers = this.patternSequence.patterns.size() - 1;
            this.score = nTransfers * 5 * 60 + distanceFromTarget;
        }

        /** Causes the PathScore instances to be sorted in order of increasing scores. */
        @Override
        public int compareTo(PathScore other) {
            return this.score - other.score;
        }
    }

    /**
     * Get the N paths that best explain or represent the specified travel time.
     * @param nPaths the maximum number of paths to return
     * @param targetTravelTimeSeconds the travel time that will be reported for this destination.
     * @return a collection of paths with nPaths or less elements. Never null, but may be empty.
     */
    public Set<PatternSequence> getTopPaths (int nPaths, int targetTravelTimeSeconds) {
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
        Set<PatternSequence> chosen = new HashSet<>();

        // Iterate over the top ranked paths, de-duplicating them and skipping over any nulls.
        for (PathScore pathScore : pathScores) {
            if (pathScore.patternSequence != null) {
                chosen.add(pathScore.patternSequence);
                if (chosen.size() == nPaths) {
                    break;
                }
            }
        }

        return chosen;
    }

}
