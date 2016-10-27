package com.conveyal.r5.analyst.scenario;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.Grid;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Compute average accessibility using Andrew Owen's method of computing instantaneous accessibility at each minute
 * and then taking an average, rather than our usual technique of computing an average/median travel time and using that
 * to compute accessibility. This is the methodology used by the University of Minnesota Accessibility Observatory
 * (and Andrew Owen, hence the name). See Owen, Andrew, and David M Levinson. 2014. “Access Across America: Transit 2014 Methodology.” CTS 14-12. Minneapolis.
 *
 * This method has a number of attractive mathematical properties, in this case the main one being that the empirical
 * distribution of accessibility is well defined. The drawback of this method is that it treats opportunities as
 * completely fungible, even day-to-day. If there are 50,000 jobs accessibility from 8:00 to 8:30 by taking an eastbound
 * commuter train, and a different 50,000 accessible from 8:30 to 9:00 by taking a westbound commuter train, this measure
 * will average out to 50,000, even though none of those 50,000 jobs are accessible for the whole time window, essentially
 * assuming that people will choose their job based on what time they leave for work on a given day. For more on this issue,
 * see Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. n.d. “Evidence-Based Transit and Land Use Sketch
 * Planning Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks,” under review for the
 * the 96th Annual Meeting of the Transportation Research Board, January 2017.
 */
public class AndrewOwenMeanGridSampler {
    private static final AmazonS3 s3 = new AmazonS3Client();

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    /** Compute a particular percentile of a grid (percentile between 0 and 100) */
    public static Grid computeMean (String resultsBucket, String key) throws IOException {
        S3Object accessGrid = s3.getObject(resultsBucket, key);

        LittleEndianDataInputStream input = new LittleEndianDataInputStream(new GZIPInputStream(accessGrid.getObjectContent()));

        char[] header = new char[8];
        for (int i = 0; i < 8; i++) {
            header[i] = (char) input.readByte();
        }

        if (!"ACCESSGR".equals(new String(header))) {
            throw new IllegalArgumentException("Input not in access grid format!");
        }

        int version = input.readInt();

        if (version != ACCESS_GRID_VERSION) {
            throw new IllegalArgumentException(String.format("Version mismatch of access grids, expected %s, found %s", ACCESS_GRID_VERSION, version));
        }

        int zoom = input.readInt();
        int west = input.readInt();
        int north = input.readInt();
        int width = input.readInt();
        int height = input.readInt();
        int nIterations = input.readInt();

        Grid grid = new Grid(zoom, width, height, north, west);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int sum = 0;
                int count = 0;

                for (int iteration = 0, val = 0; iteration < nIterations; iteration++) {
                    sum += (val += input.readInt());
                    count++;
                }

                // compute percentiles
                grid.grid[x][y] = (double) sum / count;
            }
        }

        input.close();

        return grid;
    }
}
