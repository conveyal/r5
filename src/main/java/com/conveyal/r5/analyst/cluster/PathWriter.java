package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.multipoint.MultipointDataStore;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    public final TransportNetwork network;

    /** The task used to create travel times being reduced herein */
    public final AnalysisTask task;

    List<Path> pathList;

    LittleEndianDataOutputStream outputStream;

    public PathWriter (AnalysisTask task, TransportNetwork network, List<Path> pathList, int nDestinations, int nIterations) {
        this.task = task;
        this.network = network;
        this.pathList = pathList;
        try {
            outputStream = MultipointDataStore.getOutputStream(task, task.taskId + "_paths.dat", "application/octet-stream");
            outputStream.write("PATHGRID".getBytes());
            // In the header store the number of destinations and the number of iterations at each destination
            outputStream.writeInt(nDestinations);
            outputStream.writeInt(nIterations);

            // Write the number of different paths used to reach all destination cells
            outputStream.writeInt(pathList.size());
            // Write the details for each of those paths
            for (Path path : pathList) {
                outputStream.writeInt(path.patterns.length);
                for (int i = 0 ; i < path.patterns.length; i ++){
                    outputStream.writeInt(path.boardStops[i]);
                    outputStream.writeInt(path.patterns[i]);
                    outputStream.writeInt(path.alightStops[i]);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void recordPathsForTarget (int[] paths) {
        try {
            // Write the path indexes used to reach this target at each iteration, delta coded within each target.
            int prev = 0;
            for (int pathIdx : paths) {
                outputStream.writeInt(pathIdx - prev);
                prev = pathIdx;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void finishPaths(){
        try{
            outputStream.close();
        } catch (IOException e) {
            LOG.warn("Unexpected IOException closing pathWriter OutputStream", e);
        }

    }
}
