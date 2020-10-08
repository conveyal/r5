package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.profile.Path;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Accumulates information about paths from a given origin to all destinations in a grid, then writes them out for
 * use in a static site. This could also be useful for displaying paths interactively on top of isochrones in the UI.
 *
 * This used to write one path for every departure minute / MC draw at every destination, which the client
 * filtered down to only a few for display, but that leads to huge files and a lot of post-processing.
 *
 * Users may be surprised to see an uncommon path that happens to be associated with the median travel time, so we now
 * save several different ones.
 */
public class PathWriter {

    private static final Logger LOG = LoggerFactory.getLogger(PathWriter.class);

    /** This is a symbolic value used in the output file format an in our internal primitive int maps. */
    public static final int NO_PATH = -1;

    /** The task that created the paths being recorded. */
    private final AnalysisWorkerTask task;

    /** A list of unique paths, each one associated with a positive integer index by its position in the list. */
    private final List<Path> pathForIndex = new ArrayList<>();

    /** The inverse of pathForIndex, giving the position of each path within that list. Used to deduplicate paths. */
    private final TObjectIntMap<Path> indexForPath;

    /** The total number of targets for which we're recording paths, i.e. width * height of the destination grid. */
    private final int nTargets;

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

    /** Constructor. Holds onto the task object, which is used to create unique names for the results files. */
    public PathWriter (AnalysisWorkerTask task) {
        this.task = task;
        this.nTargets = task.width * task.height;
        indexForPath = new TObjectIntHashMap<>(nTargets / 2, 0.5f, NO_PATH);
        nPathsPerTarget = task.nPathsPerTarget;
    }

    /**
     * After construction, this method is called on every destination in order.
     * The list of paths may contain nulls if there are not N transit paths to a particular target.
     * Many adjacent destinations from the same origin might use the same path, so we deduplicate them.
     * Note that if adjacent destinations have common paths, then adjacent origins
     * should also have common paths. We currently don't have an optimization to deal with that.
     *
     * @param paths a collection of paths that reach a single destination. Only the first n paths will be recorded.
     *              This collection should be pre-filtered to not include duplicate paths.
     *
     * TODO perform the creation and selection of N paths here rather than in the caller, for clearer deduplication.
     */
    public void recordPathsForTarget (Collection<Path> paths) {
        int nPathsRecorded = 0;
        for (Path path : paths) {
            if (path != null) {
                // Deduplicate paths across destinations using the map.
                int pathIndex = indexForPath.get(path);
                if (pathIndex == NO_PATH) {
                    pathIndex = pathForIndex.size();
                    pathForIndex.add(path);
                    indexForPath.put(path, pathIndex);
                }
                pathIndexes.add(pathIndex);
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

    /**
     * Once recordPathsForTarget has been called once for each target in order, this method is called to write out the
     * full set of paths to a buffer, which is then saved to S3 (or other equivalent persistence system).
     */
    public void finishAndStorePaths () {
        int nExpectedPaths = nTargets * nPathsPerTarget;
        if (pathIndexes.size() != nExpectedPaths) {
            throw new AssertionError(String.format("PathWriter expected to receive %d paths, received %d.",
                    nExpectedPaths, pathIndexes.size()));
        }
        if (pathForIndex.isEmpty()) {
            // No cells were reached with any transit paths. Do not write anything out to save storage space.
            LOG.info("No transit paths were found for task {}, not saving static site path file.", task.taskId);
            return;
        }
        // The path grid file will be built up in this buffer.
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        try {
            // Write a header, consisting of the magic letters that identify the format, followed by
            // the number of destinations and the number of paths at each destination.
            DataOutput dataOutput = persistenceBuffer.getDataOutput();
            dataOutput.write("PATHGRID".getBytes());
            dataOutput.writeInt(nTargets);
            dataOutput.writeInt(nPathsPerTarget);

            // Write the number of different distinct paths used to reach all destination cells,
            // followed by the details for each of those distinct paths.
            dataOutput.writeInt(pathForIndex.size());
            for (Path path : pathForIndex) {
                dataOutput.writeInt(path.patterns.length);
                for (int i = 0 ; i < path.patterns.length; i ++){
                    dataOutput.writeInt(path.boardStops[i]);
                    dataOutput.writeInt(path.patterns[i]);
                    dataOutput.writeInt(path.alightStops[i]);
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
        } catch (IOException e) {
            throw new RuntimeException("IO exception while writing path grid.", e);
        }
        persistenceBuffer.doneWriting();
        String pathFileName = task.taskId + "_paths.dat";
        AnalysisWorker.filePersistence.saveStaticSiteData(task, pathFileName, persistenceBuffer);
    }

}

