package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.cluster.TaskStatisticsStore;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.RaptorState;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * Compute a static site result for a single origin.
 */
public class StaticComputer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticComputer.class);
    private StaticSiteRequest.PointRequest req;
    private TransportNetwork network;
    private TaskStatistics taskStatistics;

    /** Compute the origin specified by x and y (relative to the request origin) */
    public StaticComputer (StaticSiteRequest.PointRequest req, TransportNetwork network, TaskStatistics ts) {
        this.req = req;
        this.network = network;
        this.taskStatistics = ts;
    }

    public void run () {
        // dump the times in the described format. They're small enough to keep in memory for now.
        try {
            OutputStream os = StaticDataStore.getOutputStream(req.request, req.x + "/" + req.y + ".dat", "application/octet-stream");
            write(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Error saving origin data", e);
        }
    }

    public void write (OutputStream os) throws IOException {
        WebMercatorGridPointSet points = network.getGridPointSet();
        double lat = points.pixelToLat(points.north + req.y);
        double lon = points.pixelToLon(points.west + req.x);

        TaskStatistics ts = new TaskStatistics();

        if (req.request.request.scenario != null) {
            network = network.applyScenario(req.request.request.scenario);
        }

        // perform street search to find transit stops and non-transit times
        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.distanceLimitMeters = 2000;
        sr.setOrigin(lat, lon);
        sr.route();

        // tell the Raptor Worker that we want a travel time to each stop by leaving the point set null
        RaptorWorker worker = new RaptorWorker(network.transitLayer, null, req.request.request);
        StaticPropagatedTimesStore pts = (StaticPropagatedTimesStore) worker.runRaptor(sr.getReachedStops(), null, ts);

        // get non-transit times
        // pointset around the search origin.
        WebMercatorGridPointSet subPointSet =
                new WebMercatorGridPointSet(WebMercatorGridPointSet.DEFAULT_ZOOM, points.west + req.x - 20, points.north + req.y - 20, 41, 41);
        LinkedPointSet subLinked = subPointSet.link(network.streetLayer, Mode.WALK);
        PointSetTimes nonTransitTimes = subLinked.eval(sr::getTravelTimeToVertex);

        LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(os);

        // first write out the values for nearby pixels
        out.writeInt(20);

        int previous = 0;
        for (int time : nonTransitTimes.travelTimes) {
            if (time == Integer.MAX_VALUE) time = -1;

            out.writeInt(time - previous);
            previous = time;
        }

        int iterations = pts.times.length;
        int stops = pts.times[0].length;

        // number of stops
        out.writeInt(stops);
        // number of iterations
        out.writeInt(iterations);

        double sum = 0;

        for (int stop = 0; stop < stops; stop++) {
            int prev = 0;
            int prevPath = 0;
            int maxPathIdx = 0;

            TObjectIntMap<Path> paths = new TObjectIntHashMap<>();
            List<Path> pathList = new ArrayList<>();

            for (int iter = 0; iter < iterations; iter++) {
                int time = pts.times[iter][stop];
                if (time == Integer.MAX_VALUE) time = -1;

                out.writeInt(time - prev);
                prev = time;

                // write out which path to use, delta coded
                int pathIdx = -1;

                RaptorState state = worker.statesEachIteration.get(iter);
                // only compute a path if this stop was reached
                if (state.bestNonTransferTimes[stop] != RaptorWorker.UNREACHED) {
                    Path path = new Path(state, stop);
                    if (!paths.containsKey(path)) {
                        paths.put(path, maxPathIdx++);
                        pathList.add(path);
                    }

                    pathIdx = paths.get(path);
                }

                out.writeInt(pathIdx - prevPath);
                prevPath = pathIdx;
            }

            // write the paths
            out.writeInt(pathList.size());
            for (Path path : pathList) {
                out.writeInt(path.patterns.length);

                for (int i = 0; i < path.patterns.length; i++) {
                    out.writeInt(path.boardStops[i]);
                    out.writeInt(path.patterns[i]);
                    out.writeInt(path.alightStops[i]);
                }
            }
        }

        LOG.info("Average of {} paths per destination stop", sum / stops);

        out.flush();
    }

}
