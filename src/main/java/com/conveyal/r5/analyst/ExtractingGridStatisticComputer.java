package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Extract the nth sample from a grid. Mostly useful when the grid contains a distribution rather than samples.
 */
public class ExtractingGridStatisticComputer {
    private static final AmazonS3 s3 = new AmazonS3Client();

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    public static Grid compute (String bucket, String key, int index) throws IOException {
        S3Object accessGrid = s3.getObject(bucket, key);

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

        if (index >= nIterations || index < 0) throw new ArrayIndexOutOfBoundsException("Extraction parameter out of bounds");

        Grid grid = new Grid(zoom, width, height, north, west);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = 0;
                for (int iteration = 0; iteration <= index; iteration++) {
                    val += input.readInt();
                }

                grid.grid[x][y] = val;
            }
        }

        input.close();

        return grid;
    }
}
