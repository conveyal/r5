package com.conveyal.r5.analyst.cluster;

import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility class to read and write "origin" format. Messages in this format contain the regional analysis results for
 * a single origin point, and are sent back to the GridResultQueueConsumer via an SQS Queue for assembly into one big
 * result file for the entire job. These results include some metadata about the origin point itself (coordinates) and
 * a large array of accessibility values, which are the bootstrap replications.
 *
 * TODO DELETE ME
 */
public class Origin {
    public static final Logger LOG = LoggerFactory.getLogger(Origin.class);

    /** The version of the origin file format supported by this class */
    public static final int ORIGIN_VERSION = 1;

    /** X coordinate of origin within regional analysis */
    public int x;

    /** Y coordinate of origin within regional analysis */
    public int y;

    /** Percentile of travel time used to compute these accessibility values */
    public double percentile = Double.NaN;

    /** Job ID this origin is associated with */
    public String jobId;

    /**
     * Samples of accessibility. Depending on worker version, this could be bootstrap replications of accessibility given
     * median travel time, or with older workers would be instantaneous accessibility for each departure minute.
     */
    public int[] samples;

    /** Construct an origin given a grid task and the instantaneous accessibility computed for each iteration */
    public Origin (RegionalTask request, double percentile, int[] samples) {
        this.jobId = request.jobId;
        this.percentile = percentile;
        this.x = request.x;
        this.y = request.y;
        this.samples = samples;
    }

    /** allow construction of blank origin for static read method */
    private Origin() {
        /* do nothing */
    }

    public void write(OutputStream out) throws IOException {
        LittleEndianDataOutputStream data = new LittleEndianDataOutputStream(out);

        // Write the header
        for (char c : "ORIGIN".toCharArray()) {
            data.writeByte((byte) c);
        }

        // version
        data.writeInt(ORIGIN_VERSION);

        data.writeUTF(jobId);
        data.writeDouble(percentile);
        data.writeInt(x);
        data.writeInt(y);

        // write the number of iterations
        data.writeInt(samples.length);

        for (int i : samples) {
            // don't bother to delta code, these are small and we're not gzipping
            data.writeInt(i);
        }

        data.close();
    }

    public static Origin read (InputStream inputStream) throws IOException {
        LittleEndianDataInputStream data = new LittleEndianDataInputStream(inputStream);

        // ensure that it starts with ORIGIN
        char[] header = new char[6];
        for (int i = 0; i < 6; i++) {
            header[i] = (char) data.readByte();
        }

        if (!"ORIGIN".equals(new String(header))) {
            throw new IllegalArgumentException("Origin not in proper format");
        }

        int version = data.readInt();

        if (version != ORIGIN_VERSION) {
            LOG.error("Origin version mismatch, expected {} found {}", ORIGIN_VERSION, version);
            throw new IllegalArgumentException("Origin version mismatch, expected " + ORIGIN_VERSION + ", found " + version);
        }

        Origin origin = new Origin();

        origin.jobId = data.readUTF();
        origin.percentile = data.readDouble();
        origin.x = data.readInt();
        origin.y = data.readInt();

        origin.samples = new int[data.readInt()];

        for (int iteration = 0; iteration < origin.samples.length; iteration++) {
            // de-delta-code the origin
            origin.samples[iteration] = data.readInt();
        }

        data.close();

        return origin;
    }
}
