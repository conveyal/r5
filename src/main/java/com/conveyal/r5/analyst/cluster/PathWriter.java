package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransportNetwork;
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
 * Writes path for each departure minute, and list of all paths used, from a given origin to every destination.
 *
 * This could be useful for static sites, for displaying paths in the UI on top of isochrones, etc.
 * At the moment, we return all paths (i.e. we don't reduce them); users might be confused if the path that happened to be associated with
 * the median travel time was not a commonly used one, etc.
 *
 * This implementation streams, which might be an example for how to reimplement TravelTimeSurfaceReducer.
 */
public class PathWriter {

    private static final Logger LOG = LoggerFactory.getLogger(PathWriter.class);

    /** The network used to compute the travel time results */
    private final TransportNetwork network;

    /** The task used to create travel times being reduced herein */
    private final AnalysisTask task;

    /** This map de-duplicates paths (as keys), associating each one with a zero-based integer index. */
    private TObjectIntMap<Path> indexForPath = new TObjectIntHashMap<>();

    private List<Path> pathForIndex = new ArrayList<>();

    private TIntList pathIndexForTarget = new TIntArrayList();

    public PathWriter (AnalysisTask task, TransportNetwork network) {
        this.task = task;
        this.network = network;
    }

    /**
     * This must be called on every target in order. It accepts null if there's no transit path to that target.
     */
    public void recordPathsForTarget (Path path) {
        if (path == null) {
            // This path was not reached. FIXME standardize no-entry value.
            pathIndexForTarget.add(-1);
            return;
        }
        // Deduplicate paths. TODO use no-entry value.
        if (!indexForPath.containsKey(path)) {
            int pathIndex = pathForIndex.size();
            indexForPath.put(path, pathIndex);
            pathForIndex.add(path);
        }
        int pathIndex = indexForPath.get(path);
        // Always add one path per target.
        pathIndexForTarget.add(pathIndex);
    }

    public void finishAndStorePaths () {
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        try {
            DataOutput dataOutput = persistenceBuffer.getDataOutput();
            dataOutput.write("PATHGRID".getBytes());

            // In the header, store the number of destinations and the number of paths at each destination.
            dataOutput.writeInt(pathIndexForTarget.size());
            dataOutput.writeInt(1); // Storing only one path per target now.

            // Write the number of different distinct paths used to reach all destination cells.
            dataOutput.writeInt(pathForIndex.size());

            // Write the details for each of those distinct paths.
            for (Path path : pathForIndex) {
                dataOutput.writeInt(path.patterns.length);
                for (int i = 0 ; i < path.patterns.length; i ++){
                    dataOutput.writeInt(path.boardStops[i]);
                    dataOutput.writeInt(path.patterns[i]);
                    dataOutput.writeInt(path.alightStops[i]);
                }
            }

            // Record the paths used
            pathIndexForTarget.forEach(pi -> {
                try {
                    dataOutput.write(pi);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return true; // Trove continue iterating signal.
            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        persistenceBuffer.doneWriting();
        AnalystWorker.filePersistence.saveStaticSiteData(task, "_paths.dat", persistenceBuffer);
    }

}
