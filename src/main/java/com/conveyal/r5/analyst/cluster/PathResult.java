package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.collect.Multimap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stores the path used to reach target(s) at every Raptor iteration. If the task specifies toLon and toLat
 * coordinates (i.e. a single destination), only paths to that destination are recorded.
 */

public class PathResult {

    public final int nPoints;

    // Map from path to departure times at which the path is optimal
    Multimap<Path, Integer>[] paths;

    TransitLayer transitLayer;

    public PathResult(AnalysisWorkerTask task, TransitLayer transitLayer) {
        if (task.toLat != task.fromLat &&  task.toLon != task.fromLon) {
            // TODO better way of signalling we want paths to only one destination
            nPoints = 1;
        } else {
            // Return paths to all destinations. Not yet implemented.
            nPoints = task.nTargetsPerOrigin();
        }
        paths = new Multimap[nPoints];
        this.transitLayer = transitLayer;
    }

    // At 2 million destinations and 100 int values per destination (every percentile) we still only are at 800MB.
    // So no real risk of overflowing an int index.
    public void setTarget(int targetIndex, Multimap<Path, Integer> pathsToTarget) {
        paths[targetIndex] = pathsToTarget;
    }


    public static class PathIterations {
        public String path;
        public int[] iterations;

        PathIterations(Path path, Collection<Integer> iterations, TransitLayer transitLayer) {
            if (path == null) {
                this.path = "null";
            } else {
                this.path = path.toString(transitLayer);
            }
            this.iterations = iterations.stream().mapToInt(i -> i).sorted().toArray();
        }
    }

    List<PathIterations>[] getSummaries() {
        List<PathIterations>[] summaries = new List[nPoints];

        for (int i = 0; i < nPoints; i++){
            List<PathIterations> summaryToDestination = new ArrayList<PathIterations>();
            if (paths[i] != null) {
                for (Path path : paths[i].keySet()) {
                    summaryToDestination.add(new PathIterations(path, paths[i].get(path), transitLayer));
                }
            }
            summaries[i] = summaryToDestination;
        }
        return summaries;
    }
}