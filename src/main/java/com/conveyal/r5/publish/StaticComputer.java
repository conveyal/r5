package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.cluster.TaskStatisticsStore;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

/**
 * Compute a static site result for a single origin.
 */
public class StaticComputer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticComputer.class);
    private StaticSiteRequest.PointRequest req;
    private TransportNetwork network;
    private TaskStatisticsStore taskStatisticsStore;

    /** Compute the origin specified by x and y (relative to the request origin) */
    public StaticComputer (StaticSiteRequest.PointRequest req, TransportNetwork network, TaskStatisticsStore tss) {
        this.req = req;
        this.network = network;
        this.taskStatisticsStore = tss;
    }

    public void run () {
        WebMercatorGridPointSet points = network.getGridPointSet();
        double lat = points.pixelToLat(points.north + req.y);
        double lon = points.pixelToLon(points.west + req.x);

        TaskStatistics ts = new TaskStatistics();

        LinkedPointSet lps = points.link(network.streetLayer);

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
        LinkedPointSet subLinked = subPointSet.link(network.streetLayer);
        PointSetTimes nonTransitTimes = subLinked.eval(sr::getTravelTimeToVertex);

        // dump the times in the described format. They're small enough to keep in memory for now.
        try {
            OutputStream os = StaticDataStore.getOutputStream(req.request, req.x + "/" + req.y + ".dat", "application/octet-stream");
            LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(os);

            // first write out the values for nearby pixels
            out.writeInt(20);

            int previous = 0;
            for (int time : nonTransitTimes.travelTimes) {
                out.writeInt(time - previous);
                previous = time;
            }

            int iterations = pts.times.length;
            int stops = pts.times[0].length;

            // number of stops
            out.writeInt(stops);
            // number of iterations
            out.writeInt(iterations);

            for (int stop = 0; stop < stops; stop++) {
                short prev = 0;
                for (int iter = 0; iter < iterations; iter++) {
                    int time = pts.times[iter][stop];
                    if (time == Integer.MAX_VALUE) time = -1;

                    out.writeInt(time - prev);
                    prev = (short) time;
                }
            }

            out.flush();
            out.close();
        } catch (Exception e) {
            LOG.error("Error saving origin data", e);
        }

    }
}
