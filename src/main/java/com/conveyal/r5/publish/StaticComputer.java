package com.conveyal.r5.publish;

import com.conveyal.gtfs.validator.service.GeoUtils;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.PathWithTimes;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.RaptorState;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import com.vividsolutions.jts.geom.Coordinate;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
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

    /** Compute the origin specified by x and y (relative to the request origin). Provide a network with the scenario pre-applied. */
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

        // perform street search to find transit stops and non-transit times
        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.distanceLimitMeters = 2000;
        sr.setOrigin(lat, lon);
        sr.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        sr.route();

        // tell the Raptor Worker that we want a travel time to each stop by leaving the point set null
        RaptorWorker worker = new RaptorWorker(network.transitLayer, null, req.request.request);

        TIntIntMap accessTimes = sr.getReachedStops();

        int sumStreetDistanceMm = 0;
        int sumCrowDistanceMm = 0;

        VertexStore.Vertex cursor = network.streetLayer.vertexStore.getCursor();

        for (TIntIntIterator it = accessTimes.iterator(); it.hasNext();) {
            it.advance();
            sumStreetDistanceMm += it.value();

            cursor.seek(network.transitLayer.streetVertexForStop.get(it.key()));
            Coordinate stopCoord = new Coordinate(cursor.getLon(), cursor.getLat());
            Coordinate originCoord = new Coordinate(lon, lat);

            try {
                sumCrowDistanceMm += JTS.orthodromicDistance(originCoord, stopCoord, DefaultGeographicCRS.WGS84) * 1000;
            } catch (TransformException te) {
                // do nothing
            }
            
            it.setValue(it.value() / (int) (req.request.request.walkSpeed * 1000));
        }

        StaticPropagatedTimesStore pts = (StaticPropagatedTimesStore) worker.runRaptor(accessTimes, null, ts);

        // get non-transit times
        // pointset around the search origin.
        WebMercatorGridPointSet subPointSet =
                new WebMercatorGridPointSet(WebMercatorGridPointSet.DEFAULT_ZOOM, points.west + req.x - 20, points.north + req.y - 20, 41, 41);
        LinkedPointSet subLinked = subPointSet.link(network.streetLayer, StreetMode.WALK);
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

            int previousInVehicleTravelTime = 0;
            int previousWaitTime = 0;

            for (int iter = 0; iter < iterations; iter++) {
                RaptorState state = worker.statesEachIteration.get(iter);

                int time = pts.times[iter][stop];
                if (time == Integer.MAX_VALUE) time = -1;
                else time /= 60;

                out.writeInt(time - prev);
                prev = time;

                int inVehicleTravelTime = state.inVehicleTravelTime[stop] / 60;
                out.writeInt(inVehicleTravelTime - previousInVehicleTravelTime);
                previousInVehicleTravelTime = inVehicleTravelTime;

                int waitTime = state.waitTime[stop] / 60;
                out.writeInt(waitTime - previousWaitTime);
                previousWaitTime = waitTime;

                if (waitTime > 255) {
                    LOG.info("detected excessive wait");
                }

                // write out which path to use, delta coded
                int pathIdx = -1;

                // only compute a path if this stop was reached
                if (state.bestNonTransferTimes[stop] != RaptorWorker.UNREACHED) {
                    // TODO reuse pathwithtimes?
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

            sum += pathList.size();

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
