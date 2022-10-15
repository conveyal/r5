package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.StreetMode;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates information about paths from a given origin to all destinations in a grid, then writes them out for
 * use in a static site. This could also be useful for displaying paths interactively on top of isochrones in the UI.
 * <p>
 * This used to write one path for every departure minute / MC draw at every destination, which the client
 * filtered down to only a few for display, but that leads to huge files and a lot of post-processing.
 * <p>
 * Users may be surprised to see an uncommon path that happens to be associated with the median travel time, so we now
 * save several different ones.
 */
public class TauiPathResultsWriter {
    /**
     * This is a symbolic value used in the output file format an in our internal primitive int maps.
     */
    public static final int NO_PATH = -1;

    /**
     * Version of our path grid format TODO add changelog
     */
    public static final int VERSION = 1;

    /**
     * A list of unique path templates, each one associated with a positive integer index by its position in the list.
     */
    private final List<PathResult.PathTemplate> pathForIndex = new ArrayList<>();

    /**
     * The inverse of pathForIndex, giving the position of each path within that list.
     * Used to deduplicate paths.
     */
    private final TObjectIntMap<PathResult.PathTemplate> indexForPath;

    /**
     *
     */
    public final int nTargets;

    /**
     * The number of paths being recorded at each destination location.
     * This may be much smaller than the number of iterations (MC draws). We only want to record a few paths with
     * travel times near the selected percentile of travel time.
     */
    public final int nPathsPerTarget;

    /**
     * For each target, the index number of N paths that reach that target at roughly a selected percentile
     * of all observed travel times. This is a flattened width * height * nPaths array.
     */
    private final TIntList pathIndexes = new TIntArrayList();

    public TauiPathResultsWriter(
            int nTargets,
            int nPathsPerTarget
    ) {
        this.nTargets = nTargets;
        indexForPath = new TObjectIntHashMap<>(nTargets / 2, 0.5f, NO_PATH);
        this.nPathsPerTarget = nPathsPerTarget;
    }

    /**
     * @param pathResults
     * @return true if results are indexed and ready to be written.
     */
    public boolean indexPathResults(PathResult[] pathResults) {
        for (PathResult pathResult : pathResults) {
            recordPathsForTarget(pathResult);
        }
        int nExpectedPaths = nTargets * nPathsPerTarget;
        if (pathIndexes.size() != nExpectedPaths) {
            throw new AssertionError(String.format("PathWriter expected to receive %d paths, received %d.",
                    nExpectedPaths, pathIndexes.size()));
        }
        if (pathForIndex.isEmpty()) return false;
        return true;
    }

    /**
     * After construction, this method is called on every destination in order.
     * The list of paths may contain nulls if there are not N transit paths to a particular target.
     * Many adjacent destinations from the same origin might use the same path, so we deduplicate them.
     * Note that if adjacent destinations have common paths, then adjacent origins
     * should also have common paths. We currently don't have an optimization to deal with that.
     *
     * For Taui purposes we want to deduplicate the more general PatternSequence. It has semantic hash and
     * equals methods, which Path does not as they wouldn't be used for anything in our current code. We
     * could even generalize the deduplication further to RouteSequence, see how PathResult.setTarget
     * derives RouteSequences for non-Taui cases.
     *
     * @param pathResult a collection of paths that reach a single destination. Only the first n paths will be recorded.
     *              This collection should be pre-filtered to not include duplicate paths.
     */
    private void recordPathsForTarget(PathResult pathResult) {
        var topPaths = pathResult.getFastestEntries();
        int nPathsRecorded = 0;
        for (var pathResultEntry : topPaths) {
            var pathTemplate = pathResultEntry.pathTemplate;
            if (pathTemplate.routeIndexes.size() > 0) {
                // Deduplicate paths across all destinations using this persistent map.
                int psidx = indexForPath.get(pathTemplate);
                if (psidx == NO_PATH) {
                    psidx = pathForIndex.size();
                    pathForIndex.add(pathTemplate);
                    indexForPath.put(pathTemplate, psidx);
                }
                pathIndexes.add(psidx);
                nPathsRecorded += 1;
                if (nPathsRecorded == nPathsPerTarget) {
                    break;
                }
            }
        }
        // Pad output to ensure that we record exactly the same number of paths per destination.
        while (nPathsRecorded < nPathsPerTarget) {
            pathIndexes.add(NO_PATH);
            nPathsRecorded += 1;
        }
    }

    private static byte getSingleByteCode(StreetMode mode) {
        if (mode == StreetMode.WALK) return StandardCharsets.US_ASCII.encode("W").get(0); // WALK
        if (mode == StreetMode.BICYCLE) return StandardCharsets.US_ASCII.encode("B").get(0); // BICYCLE
        return StandardCharsets.US_ASCII.encode("C").get(0); // CAR
    }

    ;

    public void writeHeader(DataOutput dataOutput) throws IOException {
        // Write a header, consisting of the magic letters that identify the format, followed by
        // the number of destinations and the number of paths at each destination.
        dataOutput.write("PATHGRID".getBytes());
        dataOutput.write("_VER".getBytes());
        dataOutput.writeInt(VERSION);
        dataOutput.writeInt(nTargets);
        dataOutput.writeInt(nPathsPerTarget);
    }

    /**
     * Once recordPathsForTarget has been called once for each target in order, this method is called to write out the
     * full set of paths.
     */
    public void writePathsAndInde(DataOutput dataOutput) throws IOException {
        // Write the number of different distinct paths used to reach all destination cells,
        // followed by the details for each of those distinct paths.
        dataOutput.writeInt(pathForIndex.size());
        for (var pathTemplate : pathForIndex) {
            dataOutput.writeInt(pathTemplate.routeIndexes.size());
            dataOutput.write(getSingleByteCode(pathTemplate.access.mode));
            dataOutput.write(getSingleByteCode(pathTemplate.egress.mode));
            for (int i = 0; i < pathTemplate.routeIndexes.size(); i++) {
                dataOutput.writeInt(pathTemplate.stopSequence.boardStops.get(i));
                dataOutput.writeInt(pathTemplate.routeIndexes.get(i));
                dataOutput.writeInt(pathTemplate.stopSequence.alightStops.get(i));
            }
        }

        // Record the paths used to reach each target in the grid. They are delta coded to improve gzip compression,
        // on the assumption that adjacent targets use paths with similar index numbers (often the same index number).
        int prevIndex = 0;
        for (TIntIterator iterator = pathIndexes.iterator(); iterator.hasNext(); ) {
            int pathIndex = iterator.next();
            int indexDelta = pathIndex - prevIndex;
            dataOutput.writeInt(indexDelta);
            prevIndex = pathIndex;
        }
    }
}

