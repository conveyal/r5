package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.conveyal.r5.transit.path.StopSequence;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.math3.util.FastMath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Holds paths and associated details from an origin to a destination target at every Raptor iteration. For
 * single-point tasks, paths to only a single destination (specified by toLon and toLat coordinates in the task) are
 * recorded.
 *
 * This class is used to accumulate paths to be returned to the broker (similar to TravelTimeResult) or passed to the
 * path writer for Taui sites.
 */
public class PathResult {
    
    /**
     * A map from a "path template" to the associated departure time
     * details. For now, the path template is a route-based path ignoring per-departure time details such as wait time.
     * With additional changes, patterns could be collapsed further to route combinations or modes.
     */
    private final Multimap<PathTemplate, Iteration> iterationsForPath = HashMultimap.create();

    public PathResult(
            TransitLayer transitLayer,
            int[] perIterationTimes,
            Path[] perIterationPaths,
            StreetTimeAndMode[] perIterationEgress
    ) {
        for (int i = 0; i < perIterationTimes.length; i++) {
            Path path = perIterationPaths[i];
            int totalTime = perIterationTimes[i];
            if (path != null) {
                TIntList routeIndexes = new TIntArrayList();
                path.patternSequence.patternIndexes.forEach(p -> routeIndexes.add(transitLayer.tripPatterns.get(p).routeIndex));
                iterationsForPath.put(
                        new PathTemplate(
                                routeIndexes,
                                path.patternSequence.stopSequence,
                                path.patternSequence.access,
                                perIterationEgress[i]
                        ),
                        new Iteration(
                                path.departureTime,
                                path.waitTimes,
                                totalTime
                        )
                );
            } else if (totalTime < FastRaptorWorker.UNREACHED) {
                iterationsForPath.put(
                        new PathTemplate(),
                        new Iteration(totalTime)
                );
            }
        }
    }

    public Set<PathTemplate> getAllPathTemplates() {
        return iterationsForPath.keySet();
    }

    public Collection<Iteration> getIterationsForPathTemplate(PathTemplate key) {
        return iterationsForPath.get(key);
    }

    public Collection<ScoredEntry> getFastestEntries() {
        var fastestPathIterations = new HashMap<PathTemplate, Iteration>();
        var pathTemplates = getAllPathTemplates();
        int fastestTimeOverall = FastRaptorWorker.UNREACHED;
        for (var pathTemplate : pathTemplates) {
            int fastestTimeForPattern = FastRaptorWorker.UNREACHED;
            for (var details : getIterationsForPathTemplate(pathTemplate)) {
                if (fastestTimeForPattern > details.totalTime) {
                    fastestPathIterations.put(pathTemplate, details);
                    fastestTimeForPattern = details.totalTime;
                }
            }
            if (fastestTimeOverall > fastestTimeForPattern) fastestTimeOverall = fastestTimeForPattern;
        }

        var entries = new ArrayList<ScoredEntry>(pathTemplates.size());
        for (var pathTemplate : pathTemplates) {
            entries.add(new ScoredEntry(pathTemplate, fastestPathIterations.get(pathTemplate), fastestTimeOverall));
        }

        Collections.sort(entries);
        return entries;
    }

    public static class PathTemplate {
        public final TIntList routeIndexes;
        public final StopSequence stopSequence;
        public final StreetTimeAndMode access;
        public final StreetTimeAndMode egress;

        PathTemplate(TIntList routeIndexes, StopSequence stopSequence, StreetTimeAndMode access, StreetTimeAndMode egress) {
            this.routeIndexes = routeIndexes;
            this.stopSequence = stopSequence;
            this.access = access;
            this.egress = egress;
        }

        PathTemplate() {
            this.routeIndexes = new TIntArrayList();
            this.stopSequence = null;
            this.access = null;
            this.egress = null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PathTemplate that = (PathTemplate) o;
            return Objects.equals(routeIndexes, that.routeIndexes) &&
                    Objects.equals(stopSequence, that.stopSequence) &&
                    Objects.equals(access, that.access) &&
                    Objects.equals(egress, that.egress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(routeIndexes, stopSequence, access, egress);
        }
    }

    public class Iteration {
        public final int departureTime;
        public final int totalTime;
        public final TIntList waitTimes;

        Iteration(int departureTime, TIntList waitTimes, int totalTime) {
            this.departureTime = departureTime;
            this.totalTime = totalTime;
            this.waitTimes = waitTimes;
        }

        Iteration(int totalTime) {
            this.departureTime = 0;
            this.totalTime = totalTime;
            this.waitTimes = new TIntArrayList();
        }
    }

    /**
     * A path template / iteration pair that is scored based on the fastest travel time overall to this destination.
     */
    class ScoredEntry implements Comparable<ScoredEntry> {
        final PathTemplate pathTemplate;
        final Iteration iteration;
        final int score;

        ScoredEntry(PathTemplate pathTemplate, Iteration iteration, int fastestTimeOverall) {
            this.pathTemplate = pathTemplate;
            this.iteration = iteration;
            this.score = computeScore(fastestTimeOverall, iteration.totalTime, pathTemplate.routeIndexes.size() - 1);
        }

        int computeScore(int targetTime, int totalTime, int nTransfers) {
            int distanceFromTarget = FastMath.abs(targetTime - totalTime);
            return nTransfers * 5 * 60 + distanceFromTarget;
        }

        @Override
        public int compareTo(ScoredEntry o) {
            return score - o.score;
        }
    }
}