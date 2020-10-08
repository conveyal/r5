package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufEncoder;
import com.conveyal.data.geobuf.GeobufFeature;
import com.conveyal.geojson.GeoJsonModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Extract Census data from a seamless datastore.
 */
public class CensusExtractor {
    /**
     * The precision to use for output files.
     * Set above 6 at your own risk; higher precision files work fine with the reference implementation and with geobuf-java,
     * but break with pygeobuf (see https://github.com/mapbox/pygeobuf/issues/21)
     */
    private static final int PRECISION = 6;

    public static void main (String... args) throws IOException {
        if (args.length < 3 || args.length > 6) {
            System.err.println("usage: CensusExtractor (s3://bucket|data_dir) n e s w [outfile.json]");
            System.err.println("   or: CensusExtractor (s3://bucket|data_dir) boundary.geojson [outfile.json]");
            return;
        }

        SeamlessSource source;
        if (!args[0].startsWith("s3://"))
            source = new FileSeamlessSource(args[0]);
        else
            source = new S3SeamlessSource(args[0].substring(5));

        long start = System.currentTimeMillis();

        Map<Long, GeobufFeature> features;

        if (args.length >= 4) {
            features = source.extract(Double.parseDouble(args[1]),
                    Double.parseDouble(args[2]),
                    Double.parseDouble(args[3]),
                    Double.parseDouble(args[4]),
                    false
            );
        }
        else {
            // read geojson boundary
            ObjectMapper om = new ObjectMapper();
            om.registerModule(new GeoJsonModule());
            FileInputStream fis = new FileInputStream(new File(args[1]));
            FeatureCollection fc = om.readValue(fis, FeatureCollection.class);
            fis.close();

            features = source.extract(fc.features.get(0).geometry, false);
        }

        OutputStream out;

        long completeTime = System.currentTimeMillis() - start;
        System.err.println("Read " + features.size() + " features in " + completeTime + "msec");

        if (args.length == 6)
            out = new FileOutputStream(new File(args[5]));
        else if (args.length == 3)
            out = new FileOutputStream(new File(args[2]));
        else
            out = System.out;

        GeobufEncoder encoder = new GeobufEncoder(out, PRECISION);
        encoder.writeFeatureCollection(features.values());
        encoder.close();

        if (out instanceof FileOutputStream)
            out.close();
    }

    // rudimentary geojson classes to deserialize feature collection

    public static class FeatureCollection {
        public String type;
        public Map<String, Object> crs;
        public List<Feature> features;
    }

    public static class Feature {
        public String type;
        public Map<String, Object> properties;
        public Geometry geometry;
    }
}
