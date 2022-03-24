package com.conveyal.analysis.controllers;

import com.conveyal.analysis.util.MapTile;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.common.GeometryUtils;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtEncoder;
import com.wdtinc.mapbox_vector_tile.adapt.jts.UserDataKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This class maintains a spatial index of data inside GTFS feeds and produces vector tiles of it for use as a
 * preview display. It is used by GtfsController, which defines the associated HTTP endpoints.
 *
 * For testing, find tile numbers with https://www.maptiler.com/google-maps-coordinates-tile-bounds-projection/
 * Examine and analyze individual tiles with https://observablehq.com/@henrythasler/mapbox-vector-tile-dissector
 *
 * The tile format specification is at https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 * To summarize:
 * Extension should be .mvt and MIME content type application/vnd.mapbox-vector-tile
 * A Vector Tile represents data based on a square extent within a projection.
 * Vector Tiles may be used to represent data with any projection and tile extent scheme.
 * The reference projection is Web Mercator; the reference tile scheme is Google's.
 * The tile should not contain information about its bounds and projection.
 * The decoder already knows the bounds and projection of a Vector Tile it requested when decoding it.
 * A Vector Tile should contain at least one layer, and a layer should contain at least one feature.
 * Feature coordinates are integers relative to the top left of the tile.
 * The extent of a tile is the number of integer units across its width and height, typically 4096.
 *
 * TODO handle cancellation of HTTP requests (Mapbox client cancels requests when zooming/panning)
 */
public class GtfsVectorTileMaker {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsVectorTileMaker.class);

    /**
     * Complex street geometries will be simplified, but the geometry will not deviate from the original by more
     * than this many tile units. These are minuscule at 1/4096 of the tile width or height.
     */
    private static final int LINE_SIMPLIFY_TOLERANCE = 5;

    // FIXME Eviction: initially just use a LoadingCache
    private final Map<String, STRtree> indexedShapes = new HashMap<>();
    private final Map<String, STRtree> indexedStops = new HashMap<>();

    private STRtree getShapesIndex(String bundleScopedId, GTFSFeed feed) {
        // Ensure only one request lazy-indexes the gtfs shapes
        synchronized (this) {
            if (!indexedShapes.containsKey(bundleScopedId)) {
                final long startTimeMs = System.currentTimeMillis();
                STRtree shapesIndex = new STRtree();
                // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
                LOG.info("{}: indexing {} patterns", bundleScopedId, feed.patterns.size());
                for (Pattern pattern : feed.patterns.values()) {
                    Route route = feed.routes.get(pattern.route_id);
                    String exemplarTripId = pattern.associatedTrips.get(0);
                    LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);
                    if (wgsGeometry == null) {
                        // Not sure why some of these are null.
                        continue;
                    }
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("id", pattern.pattern_id);
                    userData.put("name", pattern.name);
                    userData.put("routeId", route.route_id);
                    userData.put("routeName", route.route_long_name);
                    userData.put("routeColor", Objects.requireNonNullElse(route.route_color, "000000"));
                    userData.put("routeType", route.route_type);
                    wgsGeometry.setUserData(userData);
                    shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
                }
                shapesIndex.build();
                indexedShapes.put(bundleScopedId, shapesIndex);
                LOG.info("{}: pattern indexing finished in {}s", bundleScopedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
            }
            return indexedShapes.get(bundleScopedId);
        }
    }

    private STRtree getStopsIndex (String bundleScopedId, GTFSFeed feed) {
        synchronized (this) {
            if (!indexedStops.containsKey(bundleScopedId)) {
                final long startTimeMs = System.currentTimeMillis();
                STRtree stopsIndex = new STRtree();
                LOG.info("{}: indexing {} stops", bundleScopedId, feed.stops.size());
                for (Stop stop : feed.stops.values()) {
                    // This is inefficient, just bin points into mercator tiles.
                    Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
                    stopsIndex.insert(stopEnvelope, stop);
                }

                stopsIndex.build();
                indexedStops.put(bundleScopedId, stopsIndex);
                LOG.info("{}: stop indexing finished in {}s", bundleScopedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
            }
            return indexedStops.get(bundleScopedId);
        }
    }

    public byte[] getTile (String bundleScopedId, GTFSFeed feed, int zTile, int xTile, int yTile) {
        STRtree shapesIndex = getShapesIndex(bundleScopedId, feed);
        STRtree stopsIndex = getStopsIndex(bundleScopedId, feed);

        final long startTimeMs = System.currentTimeMillis();
        final int tileExtent = 4096; // Standard is 4096, smaller can in theory make tiles more compact
        Envelope wgsEnvelope = MapTile.wgsEnvelope(zTile, xTile, yTile);
        Collection<Geometry> patternGeoms = new ArrayList<>(64);
        for (LineString wgsGeometry : (List<LineString>) shapesIndex.query(wgsEnvelope)) {
            Geometry tileGeometry = clipScaleAndSimplify(wgsGeometry, wgsEnvelope, tileExtent);
            if (tileGeometry != null) {
                patternGeoms.add(tileGeometry);
            }
        }
        JtsLayer patternLayer = new JtsLayer("conveyal:gtfs:patternShapes", patternGeoms, tileExtent);

        Collection<Geometry> stopGeoms = new ArrayList<>(64);
        for (Stop stop : ((List<Stop>) stopsIndex.query(wgsEnvelope))) {
            Coordinate wgsStopCoord = new Coordinate(stop.stop_lon, stop.stop_lat);
            if (!wgsEnvelope.contains(wgsStopCoord)) {
                continue;
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
            properties.put("id", stop.stop_id);
            properties.put("name", stop.stop_name);
            properties.put("lat", stop.stop_lat);
            properties.put("lon", stop.stop_lon);

            // Factor out this duplicate code from clipScaleAndSimplify
            Coordinate tileCoord = wgsStopCoord.copy();
            tileCoord.x = ((tileCoord.x - wgsEnvelope.getMinX()) * tileExtent) / wgsEnvelope.getWidth();
            tileCoord.y = ((wgsEnvelope.getMaxY() - tileCoord.y) * tileExtent) / wgsEnvelope.getHeight();

            Point point =  GeometryUtils.geometryFactory.createPoint(tileCoord);
            point.setUserData(properties);
            stopGeoms.add(point);
        }
        JtsLayer stopsLayer = new JtsLayer("conveyal:gtfs:stops", stopGeoms, tileExtent);

        // Combine these two layers in a tile
        JtsMvt mvt = new JtsMvt(patternLayer, stopsLayer);
        MvtLayerParams mvtLayerParams = new MvtLayerParams(256, tileExtent);
        byte[] pbfMessage = MvtEncoder.encode(mvt, mvtLayerParams, new UserDataKeyValueMapConverter());

        LOG.info("getTile({}, {}, {}, {}) in {}", bundleScopedId, zTile, xTile, yTile, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
        return pbfMessage;
    }

    /**
     * Utility method reusable in other classes that produce vector tiles.
     * Convert from WGS84 to integer intra-tile coordinates, eliminating points outside the envelope
     * and reducing number of points to keep tile size down.
     *
     * We don't want to include all points of huge geometries when only a small piece of them passes through the tile.
     * This kind of clipping is considered a "standard" and not part of the vector tile specification. See:
     * https://docs.mapbox.com/data/tilesets/guides/vector-tiles-standards/#clipping
     *
     * To handle cases where a geometry passes outside the tile and then back in (e.g. U-shaped sections of routes) we
     * could break the geometry into separate pieces when it passes outside the tile, or just skip sequences of points
     * that are outside the tile, yielding a straight line from one piece to the other that's located entirely outside
     * the tile, relying on the display client to clip this out of the rendered tiles.
     *
     * When we do this, we have to make sure the movements are far enough outside the tile that they don't leave
     * artifacts inside the tile, accounting for line width, endcaps etc. so we apply a margin or buffer when deciding
     * which points are outside the tile.
     *
     * Ideally we'd move the "pen" from one place to another outside the tile with drawing switched off, but it's not
     * clear how to do this using JTS geometries. This issue would be easier to deal with if we were writing directly
     * to MBVT instead of using the GeoTools abstractions, because MBVT has separate MoveTo and LineTo commands.
     *
     * FIXME this still doesn't handle the case where a line passes through a tile but has zero points inside the tile.
     *       We will need to test whether each individual segment intersects the tile, which is a slower operation.
     *
     * @return a Geometry representing the input wgsGeometry in tile units, clipped to the wgsEnvelope with a margin,
     *         or null if the geometry has no points inside the tile.
     */
    public static Geometry clipScaleAndSimplify (LineString wgsGeometry, Envelope wgsEnvelope, int tileExtent) {
        // At first we are only reading the Coordinates so no protective copy is made.
        CoordinateSequence wgsCoordinates = wgsGeometry.getCoordinateSequence();
        boolean[] coordInsideEnvelope = new boolean[wgsCoordinates.size()];
        {
            // Add a 5% margin to the envelope make sure any artifacts are outside it.
            // This takes care of the fact that lines have endcaps and widths.
            // Unfortunately this is copying the envelope and adding a margin each time this method is called, so once
            // for each individual geometry within the tile. We can't just pass the envelope in with the margin already
            // added, because we need the true envelope to project all the coordinates into tile units.
            // We may want to refactor to process a whole collection of geometries at once to avoid this problem.
            final double bufferProportion = 0.05;
            Envelope wgsEnvWithMargin = wgsEnvelope.copy(); // Protective copy before adding margin in place.
            wgsEnvWithMargin.expandBy(
                    wgsEnvelope.getWidth() * bufferProportion,
                    wgsEnvelope.getHeight() * bufferProportion
            );
            for (int c = 0; c < wgsCoordinates.size(); c += 1) {
                coordInsideEnvelope[c] = wgsEnvWithMargin.contains(wgsCoordinates.getCoordinate(c));
            }
        }
        List<Coordinate> tileCoordinates = new ArrayList<>(wgsCoordinates.size());
        for (int c = 0; c < wgsCoordinates.size(); c += 1) {
            boolean prevInside = (c > 0) ? coordInsideEnvelope[c-1] : false;
            boolean nextInside = (c < coordInsideEnvelope.length - 1) ? coordInsideEnvelope[c+1] : false;
            boolean thisInside = coordInsideEnvelope[c];
            if (thisInside || prevInside || nextInside) {
                Coordinate coord = wgsCoordinates.getCoordinateCopy(c); // Protective copy before projecting Coordinate.
                // JtsAdapter.createTileGeom clips and uses full JTS math transform and is much too slow.
                // The following seems sufficient - tile edges should be parallel to lines of latitude and longitude.
                coord.x = ((coord.x - wgsEnvelope.getMinX()) * tileExtent) / wgsEnvelope.getWidth();
                coord.y = ((wgsEnvelope.getMaxY() - coord.y) * tileExtent) / wgsEnvelope.getHeight();
                tileCoordinates.add(coord);
                // TODO handle exit and re-enter by splitting into multiple linestrings
            }
        }
        if (tileCoordinates.size() > 1) {
            LineString tileLineString = GeometryUtils.geometryFactory.createLineString(tileCoordinates.toArray(new Coordinate[0]));
            DouglasPeuckerSimplifier simplifier = new DouglasPeuckerSimplifier(tileLineString);
            simplifier.setDistanceTolerance(LINE_SIMPLIFY_TOLERANCE);
            Geometry simplifiedTileGeometry = simplifier.getResultGeometry();
            simplifiedTileGeometry.setUserData(wgsGeometry.getUserData());
            return simplifiedTileGeometry;
        } else {
            return null;
        }
    }
}