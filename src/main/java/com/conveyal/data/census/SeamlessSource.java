package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufDecoder;
import com.conveyal.data.geobuf.GeobufFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.util.GeometricShapeFactory;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.conveyal.data.census.ShapeDataStore.lat2tile;
import static com.conveyal.data.census.ShapeDataStore.lon2tile;

/**
 * A tile source for seamless Census extracts
 */
public abstract class SeamlessSource {
    // convenience
    private static final int ZOOM_LEVEL = ShapeDataStore.ZOOM_LEVEL;

    protected static final Logger LOG = LoggerFactory.getLogger(SeamlessSource.class);

    private static final GeometryFactory geometryFactory = new GeometryFactory();

    /** Extract features by bounding box */
    public Map<Long, GeobufFeature> extract(double north, double east, double south, double west, boolean onDisk) throws
            IOException {
        GeometricShapeFactory factory = new GeometricShapeFactory(geometryFactory);
        factory.setCentre(new Coordinate((east + west) / 2, (north + south) / 2));
        factory.setWidth(east - west);
        factory.setHeight(north - south);
        Polygon rect = factory.createRectangle();
        return extract(rect, onDisk);
    }

    /** Extract features by arbitrary polygons */
    public Map<Long, GeobufFeature> extract(Geometry bounds, boolean onDisk) throws IOException {
        Map<Long, GeobufFeature> ret;

        if (onDisk)
            ret = DBMaker.newTempTreeMap();
        else
            ret = new HashMap<>();

        Envelope env = bounds.getEnvelopeInternal();
        double west = env.getMinX(), east = env.getMaxX(), north = env.getMaxY(), south = env.getMinY();

        // TODO: use prepared polygons

        // figure out how many tiles we're requesting
        int minX = lon2tile(west, ZOOM_LEVEL), maxX = lon2tile(east, ZOOM_LEVEL),
                minY = lat2tile(north, ZOOM_LEVEL), maxY = lat2tile(south, ZOOM_LEVEL);

        int tcount = (maxX - minX + 1) * (maxY - minY + 1);

        LOG.info("Requesting {} tiles", tcount);

        int fcount = 0;

        // read all the relevant tiles
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                InputStream is = getInputStream(x, y);

                if (is == null)
                    // no data in this tile
                    continue;

                // decoder closes input stream as soon as it has read the tile
                GeobufDecoder decoder = new GeobufDecoder(new GZIPInputStream(new BufferedInputStream(is)));

                while (decoder.hasNext()) {
                    GeobufFeature f = decoder.next();
                    // blocks are duplicated at the edges of tiles, no need to import twice
                    if (ret.containsKey(f.numericId))
                        continue;

                    if (!bounds.disjoint(f.geometry)) {
                        ret.put(f.numericId, f);
                        fcount++;

                        if (fcount % 1000 == 0)
                            LOG.info("Read {} features", fcount);
                    }
                }
            }
        }

        return ret;
    }

    /** get an input stream for the given tile */
    protected abstract InputStream getInputStream(int x, int y) throws IOException;
}
