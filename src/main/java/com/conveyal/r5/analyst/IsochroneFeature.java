package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.operation.union.UnaryUnionOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * This is similar to the IsochroneData class in OTP, and in fact for compatibility can be serialized to JSON and
 * deserialized as such. However the code is entirely new.
 */
public class IsochroneFeature implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(IsochroneFeature.class);

    public static final Geometry EMPTY_POLYGON = GeometryUtils.geometryFactory.createPolygon(new Coordinate[0]);

    private static final long serialVersionUID = 1L;

    // maximum number of vertices in a ring
    public static final int MAX_RING_SIZE = 25000;

    // scale factor for the grid
    public static final int SCALE_FACTOR = 1;

    public Geometry geometry;
    public int cutoffSec;

    public IsochroneFeature () { /* deserialization */ }

    /**
     * Create an isochrone for the given cutoff, using a Marching Squares algorithm.
     * https://en.wikipedia.org/wiki/Marching_squares
     */
    public IsochroneFeature (int cutoffSec, WebMercatorGridPointSet points, int[] times) {
        LOG.debug("Making isochrone for {}sec", cutoffSec);
        this.cutoffSec = cutoffSec;
        // make contouring grid
        byte[][] contour = new byte[(int) points.width / SCALE_FACTOR - 1][(int) points.height / SCALE_FACTOR - 1];
        for (int y = 0; y < points.height / SCALE_FACTOR - 1; y++) {
            for (int x = 0; x < points.width / SCALE_FACTOR - 1; x++) {
                boolean topLeft = times[(int) (points.width * y + x) * SCALE_FACTOR] < cutoffSec;
                boolean topRight = times[(int) (points.width * y + x + 1) * SCALE_FACTOR] < cutoffSec;
                boolean botLeft = times[(int) (points.width * (y + 1) + x) * SCALE_FACTOR] < cutoffSec;
                boolean botRight = times[(int) (points.width * (y + 1) + x + 1) * SCALE_FACTOR] < cutoffSec;

                byte idx = 0;

                // TODO saddle points. Do we care?

                if (topLeft) idx |= 1 << 3;
                if (topRight) idx |= 1 << 2;
                if (botRight) idx |= 1 << 1;
                if (botLeft) idx |= 1;

                contour[x][y] = idx;
            }
        }

        LOG.debug("Contour grid built");

        // create a geometry. For now not doing linear interpolation. Find a cell a line crosses through and
        // follow that line.
        List<Polygon> rings = new ArrayList<>();
        boolean[][] found = new boolean[(int) points.width - 1][(int) points.height - 1];

        for (int y = 0; y < points.height / SCALE_FACTOR - 1; y++) {
            for (int x = 0; x < points.width / SCALE_FACTOR - 1; x++) {
                if (found[x][y]) continue;

                byte idx = contour[x][y];

                found[x][y] = true;

                // can't start at a saddle we don't know which way it goes
                if (idx == 0 || idx == 5 || idx == 10 || idx == 15) continue;

                int origx = x, origy = y;

                byte prevIdx = -1;

                List<Coordinate> ring = new ArrayList<>();
                // skip empty cells
                int prevy = 0, prevx = 0;
                CELLS:
                while (true) {
                    idx = contour[x][y];
                    found[x][y] = true;

                    // TODO isolines don't pass through the centers of cells
                    if (idx != prevIdx)
                        // not continuing in same direction
                        ring.add(new Coordinate(points.pixelToLon(x * SCALE_FACTOR + points.west), points.pixelToLat(y * SCALE_FACTOR + points.north)));

                    // follow line, keeping unfilled area to the left, which determines a direction
                    // this also means that we'll be able to figure out if something is a hole by
                    // the winding direction.
                    boolean end = ring.size() >= MAX_RING_SIZE;
                    switch (idx) {
                        case 0:
                            end = true;
                            break;
                        case 1:
                            x--;
                            break;
                        // NB: +y is down
                        case 2:
                            y++;
                            break;
                        case 3:
                            x--;
                            break;
                        case 4:
                            x++;
                            break;
                        case 5:
                            if (prevy > y)
                                // came from bottom
                                x++;
                            else if (prevx < x)
                                // came from left
                                y--;
                            else if (prevy < y)
                                // came from top
                                x--;
                            else
                                // came from right
                                y++;
                            break;
                        case 6:
                            y++;
                            break;
                        case 7:
                            x--;
                            break;
                        case 8:
                            y--;
                            break;
                        case 9:
                            y--;
                            break;
                        case 10:
                            if (prevy > y)
                                // came from bottom
                                x--;
                            else if (prevx < x)
                                // came from left
                                y++;
                            else if (prevy < y)
                                // came from top
                                x++;
                            else
                                // came from right
                                y--;
                            break;
                        case 11:
                            y--;
                            break;
                        case 12:
                            x++;
                            break;
                        case 13:
                            x++;
                            break;
                        case 14:
                            y++;
                            break;
                        case 15:
                            end = true;
                            break;
                    }

                    // this shouldn't happen
                    if (x == prevx && y == prevy)
                        end = true;

                    prevIdx = idx;
                    prevx = x;
                    prevy = y;

                    if (x == origx && y == origy || end) {
                        if (end)
                            LOG.warn("Ring ended unexpectedly");

                        ring.add(ring.get(0));
                        if (ring.size() != 2 && ring.size() != 3)
                            rings.add(GeometryUtils.geometryFactory.createPolygon(ring.toArray(new Coordinate[ring.size()])));
                        else
                            LOG.warn("Ring with two points, this should not happen");

                        break CELLS;
                    }
                }
            }
        }

        LOG.debug("{} components", rings.size());

        // find the largest ring, more or less (ignoring lon scale distortion)
        // FIXME this won't work
        double maxArea = 0;
        Geometry largestRing = EMPTY_POLYGON;

        for (Polygon ring : rings) {
            double area = ring.getArea();
            if (maxArea < area) {
                maxArea = area;
                largestRing = ring;
            }
        }

        // first geometry has to be an outer ring, but there may be multiple outer rings
        this.geometry = largestRing;

        LOG.debug("Done.");
    }
}
