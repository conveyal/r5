package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.operation.union.UnaryUnionOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
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

    // scale factor for the grid. Making this a factor of 2 will theoretically make the algorithm fast as the averaging
    // is just a bit shift (but who knows what the JVM will decide to optimize)
    public static final int SCALE_FACTOR = 4;

    public Geometry geometry;
    public int cutoffSec;

    public IsochroneFeature () { /* deserialization */ }

    /**
     * Create an isochrone for the given cutoff, using a Marching Squares algorithm.
     * https://en.wikipedia.org/wiki/Marching_squares
     */
    public IsochroneFeature (int cutoffSec, WebMercatorGridPointSet points, int[] times) {
        /*try {
            FileWriter w = new FileWriter(new File("times" + cutoffSec + ".txt"));
            for (int y = 0, pixel = 0; y < points.height; y++) {
                for (int x = 0; x < points.width; x++, pixel++) {
                    w.write(times[pixel] < cutoffSec ? "XX" : "  ");
                }
                w.write("\n");
            }
        } catch (Exception e) {
            LOG.error("could not dump times", e);
        }*/

        LOG.debug("Making isochrone for {}sec", cutoffSec);
        this.cutoffSec = cutoffSec;
        // make contouring grid
        byte[][] contour = new byte[(int) points.width - 1][(int) points.height - 1];
        for (int y = 0; y < points.height - 1; y++) {
            for (int x = 0; x < points.width - 1; x++) {
                boolean topLeft = times[(int) (points.width * y + x)] < cutoffSec;
                boolean topRight = times[(int) (points.width * y + x + 1)] < cutoffSec;
                boolean botLeft = times[(int) (points.width * (y + 1) + x)] < cutoffSec;
                boolean botRight = times[(int) (points.width * (y + 1) + x + 1)] < cutoffSec;

                byte idx = 0;

                // TODO saddle points. Do we care?

                if (topLeft) idx |= 1 << 3;
                if (topRight) idx |= 1 << 2;
                if (botRight) idx |= 1 << 1;
                if (botLeft) idx |= 1;

                contour[x][y] = idx;
            }
        }

        /*try {
            dumpContourGrid(contour, "contour" + cutoffSec + ".txt");
        } catch (Exception e) {
            LOG.error("Could not dump contour grid", e);
        }*/

        // create a geometry. For now not doing linear interpolation. Find a cell a line crosses through and
        // follow that line.
        List<Polygon> rings = new ArrayList<>();
        boolean[][] found = new boolean[(int) points.width - 1][(int) points.height - 1];

        for (int y = 0; y < points.height - 1; y++) {
            for (int x = 0; x < points.width - 1; x++) {
                if (found[x][y]) continue;

                byte idx = contour[x][y];

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

                    if (found[x][y] && idx != 5 && idx != 10) {
                        LOG.error("Ring crosses another ring (possibly itself). This cell has index {}, the previous cell has index {}.", idx, prevIdx);
                        break CELLS;
                    }

                    found[x][y] = true;

                    // follow line, keeping unfilled area to the left, which determines a direction
                    // this also means that we'll be able to figure out if something is a hole by
                    // the winding direction.
                    if (ring.size() >= MAX_RING_SIZE) {
                        LOG.error("Ring is too large, bailing");
                        break CELLS;
                    }

                    // save x values here, the next iteration may need to know what they were before we messed with them
                    int startx = x;
                    int starty = y;
                    switch (idx) {
                        case 0:
                            LOG.error("Ran off outside of ring");
                            break CELLS;
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
                            else if (prevy < y)
                                // came from top
                                x--;
                            else
                                LOG.error("Entered case 5 saddle point from wrong direction!");
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
                            if (prevx < x)
                                // came from left
                                y++;
                            else if (prevx > x)
                                // came from right
                                y--;
                            else {
                                LOG.error("Entered case 10 saddle point from wrong direction.");
                            }
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
                            LOG.error("Ran off inside of ring");
                            break CELLS;
                    }

                    // figure out from whence we came
                    int topLeftTime = times[(int) points.width * y + x];
                    int botLeftTime = times[(int) points.width * (y + 1) + x];
                    int topRightTime = times[(int) points.width * y + x + 1];
                    int botRightTime = times[(int) points.width * (y + 1) + x + 1];

                    double lat, lon;

                    if (startx < x) {
                        // came from left
                        // will always be positive, if numerator is negative denominator will be as well.
                        double frac = (cutoffSec - topLeftTime) / (double) (botLeftTime - topLeftTime);
                        lat = points.pixelToLat(points.north + y + frac);
                        lon = points.pixelToLon(points.west + x);
                    }
                    else if (startx > x) {
                        // came from right
                        double frac = (cutoffSec - topRightTime) / (double) (botRightTime - topRightTime);
                        lat = points.pixelToLat(points.north + y + frac);
                        lon = points.pixelToLon(points.west + x + 1);
                    }
                    else if (starty < y) {
                        // came from top
                        double frac = (cutoffSec - topLeftTime) / (double) (topRightTime - topLeftTime);
                        lat = points.pixelToLat(points.north + y);
                        lon = points.pixelToLon(points.west + x + frac);
                    }
                    else {
                        // came from bottom
                        double frac = (cutoffSec - botLeftTime) / (botRightTime - botLeftTime);
                        lat = points.pixelToLat(points.north + y + 1);
                        lon = points.pixelToLon(points.west + x + frac);
                    }

                    ring.add(new Coordinate(lon, lat));

                    // this shouldn't happen
                    if (x == startx && y == starty) {
                        LOG.error("Ring position did not update");
                        break CELLS;
                    }

                    // pass previous values to next iteration
                    prevIdx = idx;
                    prevx = startx;
                    prevy = starty;

                    if (x == origx && y == origy) {
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

    public void dumpContourGrid (byte[][] contour, String out) throws Exception {
        FileWriter sb = new FileWriter(new File(out));
        for (int y = 0; y < contour[0].length; y++) {
            for (int x = 0; x < contour.length; x++) {
                switch(contour[x][y]) {
                    case 0:
                        sb.append("  ");
                        break;
                    case 15:
                        sb.append("XX");
                        break;
                    case 1:
                    case 14:
                        sb.append("\\ ");
                        break;
                    case 2:
                    case 13:
                        sb.append(" /");
                        break;
                    case 3:
                    case 12:
                        sb.append("--");
                        break;
                    case 4:
                    case 11:
                        sb.append(" \\");
                        break;
                    case 5:
                        sb.append("//");
                        break;
                    case 10:
                        sb.append("\\\\");
                        break;
                    case 6:
                    case 9:
                        sb.append("| ");
                        break;
                    case 7:
                    case 8:
                        sb.append("/ ");
                        break;
                    default:
                        sb.append("**");

                }
            }
            sb.append("\n");
        }

        sb.close();
    }
}
