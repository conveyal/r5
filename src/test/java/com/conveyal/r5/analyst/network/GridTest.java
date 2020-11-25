package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

import java.io.FileOutputStream;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * Created by abyrd on 2020-11-20
 *
 * TODO option to align grid exactly with sample points in WebMercatorGridPointSet to eliminate walking time
 *      and check splitting
 */
public class GridTest {

    @Test
    public void testGrid () throws Exception {
        Coordinate simpsonDesert = new CoordinateXY(136.5, -25.5);
        GridLayout gridLayout = new GridLayout(simpsonDesert, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addHorizontalRoute(40, 20);
        gridLayout.addHorizontalRoute(60, 20);
        gridLayout.addVerticalRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        // Logging and file export for debugging:
        // System.out.println("Grid envelope: " + gridLayout.gridEnvelope());
        // gridLayout.exportFiles("test");

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .morningPeak()
                .setOrigin(20, 20)
                .uniformOpportunityDensity(10)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // Write travel times to Geotiff for debugging visualization in desktop GIS:
        // toGeotiff(oneOriginResult, task);

        // Now to verify the results. We have only our 5 percentiles here, not the full list of travel times.
        // They are also arranged on a grid. This grid does not match the full extents of the network, rather it
        // matches the extents set in the task, which must exactly match those of the opportunity grid.
        Coordinate destLatLon = gridLayout.getIntersectionLatLon(40, 40);
        // Here is a bit of awkwardness where WebMercatorGridPointSet and Grid both extend PointSet, but don't share
        // their grid referencing code, so one would have to be converted to the other to get the point index.
        int pointIndex = new WebMercatorGridPointSet(task.getWebMercatorExtents()).getPointIndexContaining(destLatLon);

        // Transit takes 30 seconds per block. Mean wait time is 10 minutes. Any trip takes one transfer.
        // 20+20 blocks at 30 seconds each = 20 minutes. Two waits at 0-20 minutes each, mean 20 minutes.
        // Board slack is 2 minutes. With pure frequency routes total should be 24 to 64 minutes, median 44.
        // However, these are syncrhonized scheduled, not pure frequencies, where the transfer wait is always 10 minutes.
        // So scheduled range is expected to be 2 minutes slack, 0-20 minutes wait, 10 minutes ride, 10 minutes wait,
        // 10 minutes ride, giving 32 to 52 minutes.
        for (int p = 0; p < 5; p++) {
            int travelTimeMinutes = oneOriginResult.travelTimes.getValues()[p][pointIndex];
            System.out.printf(
                "percentile %d %s\n",
                p,
                (travelTimeMinutes == UNREACHED) ? "NONE" : Integer.toString(travelTimeMinutes) + "minutes"
            );
        }

    }

    /** Write travel times to GeoTiff. Convenience method to help visualize results in GIS while developing tests. */
    private static void toGeotiff (OneOriginResult oneOriginResult, AnalysisWorkerTask task) {
        try {
            TimeGridWriter timeGridWriter = new TimeGridWriter(oneOriginResult.travelTimes, task);
            timeGridWriter.writeGeotiff(new FileOutputStream("traveltime.tiff"));
        } catch (Exception e) {
            throw new RuntimeException("Could not write geotiff.", e);
        }
    }

}
