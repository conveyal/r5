package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalystClusterRequest;
import com.conveyal.r5.analyst.cluster.GenericClusterRequest;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.protobuf.CodedOutputStream;
import gnu.trove.map.TIntIntMap;
import org.apache.commons.math3.random.MersenneTwister;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.BitSet;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

/**
 * See how big storing accessibility trees for every transit stop is.
 */
public class StaticSiteTest {
    public static void main (String... args) throws Exception {
        TransportNetwork tn = TransportNetwork.fromDirectory(new File("."));
        // run a search from each transit stop
        int[] sum = new int[] { 0 };
        int[] count = new int[] { 0 };

        int max = tn.transitLayer.streetVertexForStop.size();

        // make a pointset for all the dest stops
        FreeFormPointSet pset = new FreeFormPointSet(max);
        VertexStore.Vertex v = tn.streetLayer.vertexStore.getCursor();

        for (int i = 0; i < max; i++) {
            v.seek(i);
            PointFeature pf = new PointFeature();
            pf.setLat(v.getLat());
            pf.setLon(v.getLon());
            pset.addFeature(pf, i);
        }

        LinkedPointSet lpset = new LinkedPointSet(pset, tn.streetLayer);

        MersenneTwister mt = new MersenneTwister();
        (IntStream.range(0, 1000)).parallel().forEach(stop -> {
            int vidx = -1;

            while (vidx == -1) {
                stop = mt.nextInt(max);
                vidx = tn.transitLayer.streetVertexForStop.get(stop);
            }

            v.seek(vidx);
            AnalystClusterRequest acr = new AnalystClusterRequest();

            ProfileRequest req = new ProfileRequest();
            req.fromLat = v.getLat();
            req.fromLon = v.getLon();
            req.fromTime = 7 * 3600;
            req.toTime = 9 * 3600;

            RaptorWorker worker = new RaptorWorker(tn.transitLayer, lpset, req);

            // extract the full travel time matrix
            int[][][] fullTimes = new int[1][][];

            worker.propagatedTimesStore = new PropagatedTimesStore(lpset.size()) {
                @Override
                public void setFromArray(int[][] times, BitSet includeInAverages, ConfidenceCalculationMethod confidenceCalculationMethod) {
                    super.setFromArray(times, includeInAverages, confidenceCalculationMethod);
                    // dodge stupid effectively final nonsense
                    fullTimes[0] = times;
                }
            };

            StreetRouter streetRouter = new StreetRouter(tn.streetLayer);
            // TODO add time and distance limits to routing, not just weight.
            // TODO apply walk and bike speeds and maxBike time.
            streetRouter.distanceLimitMeters = 2000; // FIXME arbitrary, and account for bike or car access mode
            streetRouter.setOrigin(req.fromLat, req.fromLon);
            streetRouter.route();

            // Find the travel time to every target without using any transit, based on the results in the StreetRouter.
            PointSetTimes nonTransitTimes = lpset.eval(streetRouter::getTravelTimeToVertex);
            TIntIntMap reachedStops = streetRouter.getReachedStops();

            worker.runRaptor(reachedStops, nonTransitTimes, new TaskStatistics());

            // delta-code, zigzag, gzip and write out the data
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(bos);
                CodedOutputStream cos = CodedOutputStream.newInstance(gzos);

                // write out the number of minutes and number of targets
                // recall that fullTimes is wrapped in a single-element array to dodge the effectively final nonsense
                // targets
                cos.writeInt32NoTag(fullTimes[0][0].length);
                // minutes
                cos.writeInt32NoTag(fullTimes[0].length);

                for (int target = 0; target < fullTimes[0][0].length; target++) {
                    int prev = 0;
                    for (int minute = 0; minute < fullTimes[0].length; minute++) {
                        int val = fullTimes[0][minute][target];
                        cos.writeInt32NoTag(val - prev);
                        prev = val;
                    }
                }

                cos.flush();
                gzos.close();

                synchronized (StaticSiteTest.class) {
                    count[0]++;
                    sum[0] += bos.size();
                }
            } catch (Exception e) {
                // rethrow as unchecked
                throw new RuntimeException(e);
            }
        });

        System.out.println(String.format("Average compressed size: %.3fb", sum[0] / (double) count[0]));
    }
}
