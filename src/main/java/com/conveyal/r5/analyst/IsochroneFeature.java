package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This is similar to the IsochroneData class in OTP, and in fact for compatibility can be serialized to JSON and
 * deserialized as such. However it uses a completely different algorithm.
 *
 * Although we have another separate implementaion of the marching squares contour line algorithm in Javascript,
 * we're holding on to this one in case we need an R5 server to generate vector isochrones itself.
 */
public class IsochroneFeature implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(IsochroneFeature.class);

    private static final long serialVersionUID = 1L;

    // maximum number of vertices in a ring
    public static final int MAX_RING_SIZE = 25000;

    /** The minimum ring size (to get rid of small rings). Should be at least 4 to ensure all rings are valid */
    public static final int MIN_RING_SIZE = 12;

    public MultiPolygon geometry;
    public int cutoffSec;

    public IsochroneFeature () { /* deserialization */ }

    /**
     * Create an isochrone for the given cutoff, using a Marching Squares algorithm.
     * https://en.wikipedia.org/wiki/Marching_squares
     */
    public IsochroneFeature (int cutoffSec, WebMercatorGridPointSet points, int[] times) {
        // slightly hacky, but simple: set all of the times around the edges of the pointset to MAX_VALUE so that
        // the isochrone never runs off the edge of the display.
        // first, protective copy
        times = Arrays.copyOf(times, times.length);

        for (int x = 0; x < points.width; x++) {
            times[x] = Integer.MAX_VALUE;
            times[(int) ((points.height - 1) * points.width) + x] = Integer.MAX_VALUE;
        }

        for (int y = 0; y < points.height; y++) {
            times[(int) points.width * y] = Integer.MAX_VALUE;
            times[(int) points.width * (y + 1) - 1] = Integer.MAX_VALUE;
        }

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

        // create a geometry. For now not doing linear interpolation. Find a cell a line crosses through and
        // follow that line.
        List<LinearRing> outerRings = new ArrayList<>();
        List<LinearRing> innerRings = new ArrayList<>();
        boolean[][] found = new boolean[(int) points.width - 1][(int) points.height - 1];

        for (int origy = 0; origy < points.height - 1; origy++) {
            for (int origx = 0; origx < points.width - 1; origx++) {
                int x = origx;
                int y = origy;

                if (found[x][y]) continue;

                byte idx = contour[x][y];

                // can't start at a saddle we don't know which way it goes
                if (idx == 0 || idx == 5 || idx == 10 || idx == 15) continue;

                byte prevIdx = -1;

                List<Coordinate> ring = new ArrayList<>();

                // keep track of clockwise/counterclockwise orientation, see http://stackoverflow.com/questions/1165647
                int direction = 0;
                // skip empty cells
                int prevy = 0, prevx = 0;
                Coordinate prevCoord = null;
                CELLS:
                while (true) {
                    idx = contour[x][y];

                    // check for intersecting rings, but know that saddles are supposed to self-intersect.
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
                    // NB no bounds checking is performed below, but the next iteration of the loop will try to access contour[x][y] which
                    // will serve as a bounds check.
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

                    // keep track of winding direction
                    // http://stackoverflow.com/questions/1165647
                    direction += (x - startx) * (y + starty);

                    prevCoord = new Coordinate(lon, lat);
                    ring.add(prevCoord);

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
                        Coordinate end = ring.get(0);
                        ring.add(end);

                        if (ring.size() > MIN_RING_SIZE) {
                            LinearRing lr = GeometryUtils.geometryFactory.createLinearRing(ring.toArray(new Coordinate[ring.size()]));
                            // direction less than 0 means clockwise (NB the y-axis is backwards), since value is to left it is an outer ring
                            if (direction > 0) {
                                // simplify so point in polygon test is tractable
                                lr = (LinearRing) TopologyPreservingSimplifier.simplify(lr, 1e-3);
                                outerRings.add(lr);
                            } else {
                                innerRings.add(lr);
                            }
                        }

                        break CELLS;
                    }
                }
            }
        }

        LOG.debug("{} components", outerRings.size());

        Multimap<LinearRing, LinearRing> holesForRing = HashMultimap.create();

        // create polygons so we can test containment
        Map<LinearRing, Polygon> polygonsForOuterRing = outerRings.stream().collect(Collectors.toMap(
                r -> r,
                r -> GeometryUtils.geometryFactory.createPolygon(r)
        ));

        Map<LinearRing, Polygon> polygonsForInnerRing = innerRings.stream().collect(Collectors.toMap(
                r -> r,
                r -> GeometryUtils.geometryFactory.createPolygon(r)
        ));

        // put the biggest ring first because most holes are in the biggest ring, reduces number of point in polygon tests below
        outerRings.sort(Comparator.comparing(ring -> polygonsForOuterRing.get(ring).getArea()).reversed());

        // get rid of tiny shells


        LOG.info("Found {} outer rings and {} inner rings for cutoff {}m", polygonsForOuterRing.size(), polygonsForInnerRing.size(), cutoffSec / 60);

        int holeIdx = -1;
        HOLES: for (Map.Entry<LinearRing, Polygon> hole : polygonsForInnerRing.entrySet()) {
            holeIdx++;

            // get rid of tiny holes
            if (hole.getValue().getArea() < 1e-6) continue;

            for (LinearRing ring : outerRings) {
                // fine to test membership of first coordinate only since shells and holes are disjoint, and holes
                // nest completely in shells
                if (polygonsForOuterRing.get(ring).contains(hole.getKey().getPointN(0))) {
                    holesForRing.put(ring, hole.getKey());
                    continue HOLES;
                }
            }

            LOG.warn("Found no fitting shell for isochrone hole {} at cutoff {}, dropping this hole.", holeIdx, cutoffSec);
        }

        Polygon[] polygons = outerRings.stream().map(shell -> {
            Collection<LinearRing> holes = holesForRing.get(shell);
            return GeometryUtils.geometryFactory.createPolygon(shell, holes.toArray(new LinearRing[holes.size()]));
        }).toArray(s -> new Polygon[s]);

        // first geometry has to be an outer ring, but there may be multiple outer rings
        this.geometry = GeometryUtils.geometryFactory.createMultiPolygon(polygons);

        LOG.debug("Done.");
    }

    /**
     * Debug code to draw an ascii-art isochrone.
     */
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
