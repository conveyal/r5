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
import java.util.List;

/**
 * Accumulates information about paths from a given origin to all destinations in a grid, then writes them out for
 * use in a static site. This could also be useful for displaying paths in the UI on top of isochrones.
 *
 * This used to write one path for every departure minute (and every MC draw) at every destination, which the client
 * filtered down to only a few for display, but that leads to huge files and a lot of post-processing.
 * Users may be surprised to see an uncommon path that happenes to be associated with the median travel time.
 */
public class PathWriter {

    private static final Logger LOG = LoggerFactory.getLogger(PathWriter.class);

    /** This is a symbolic value used in the output file format an in our internal primitive int maps. */
    public static final int NO_PATH = -1;

    /** The task that created the paths being recorded. */
    private final AnalysisTask task;

    /** A list of unique paths, each one associated with a positive integer index by its position in the list. */
    private final List<Path> pathForIndex = new ArrayList<>();

    /** The inverse of pathForIndex, giving the position of each path within that list. Used to deduplicate paths. */
    private final TObjectIntMap<Path> indexForPath;

    public final int nTargets;

    public final int nPathsPerTarget;

    /**
     * For each target, the index number of the path that was used to reach that target at a selected percentile
     * of travel time.
     */
    private final TIntList pathIndexes = new TIntArrayList();

    /** Constructor. Holds onto the task object, which is used to create unique names for the results files. */
    public PathWriter (AnalysisTask task, int nPathsPerTarget) {
        this.task = task;
        this.nTargets = task.width * task.height;
        indexForPath = new TObjectIntHashMap<>(nTargets / 2, 0.5f, NO_PATH);
        this.nPathsPerTarget = nPathsPerTarget;
    }

    /**
     * After construction, this method is called on every target in order. It accepts null if there's no transit path
     * to a particular target. Currently we're recording only one path per target, but retaining the path deduplication
     * code on the assumption that many adjacent destinations might use the same path, and we might want multiple paths
     * per destination in the future. Note that if adjacent destinations have common paths, then adjacent origins
     * should also have common paths. We currently don't have an optimization to deal with that.
     */
    public void recordPathsForTarget (List<Path> paths) {
        if (paths.size() != nPathsPerTarget) {
            throw new AssertionError("Must supply the expected number of paths: " + nPathsPerTarget);
        }
        for (Path path : paths) {
            if (path == null) {
                pathIndexes.add(NO_PATH);
            } else {
                // Deduplicate paths using the map.
                int pathIndex = indexForPath.get(path);
                if (pathIndex == NO_PATH) {
                    pathIndex = pathForIndex.size();
                    pathForIndex.add(path);
                    indexForPath.put(path, pathIndex);
                }
                pathIndexes.add(pathIndex);
            }
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
        AnalystWorker.filePersistence.saveStaticSiteData(task, "_paths.dat", persistenceBuffer);
    }

}
