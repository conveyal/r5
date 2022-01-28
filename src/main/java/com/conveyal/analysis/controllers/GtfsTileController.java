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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines HTTP API endpoints to return Mapbox vector tiles of GTFS feeds known to the Analysis backend.
 * For the moment this is just a basic proof of concept.
 *
 * A basic example client for browsing the tiles is at src/main/resources/vector-client
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
public class GtfsTileController {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsTileController.class);

    // FIXME Super bad: one spatial index for all GTFS feeds. Ideally indexes could be shared with gtfs-api / gtfs-lib.
    //  Also consider indexing by rasterizing into high-zoom web mercator tiles as bins, instead of general purpose
    //  rectangle-tree. May also be integrated with efforts to convert general GraphQL API to few specialized endpoints.
    private final STRtree shapesIndex = new STRtree();

    private final STRtree stopsIndex = new STRtree();

    private final Set<String> indexedGtfs = new HashSet<>();

    public byte[] getTile (String bundleScopedId, GTFSFeed feed, int zTile, int xTile, int yTile) {
        // Ensure only one request lazy-indexes the gtfs shapes
        synchronized (this) {
            if (!indexedGtfs.contains(bundleScopedId)) {
                // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
                LOG.info("Indexing {} patterns", feed.patterns.size());
                for (Pattern pattern : feed.patterns.values()) {
                    Route route = feed.routes.get(pattern.route_id);
                    String exemplarTripId = pattern.associatedTrips.get(0);
                    LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);

                    Map<String, Object> userData = new HashMap<>();
                    userData.put("name", route.route_long_name);
                    userData.put("color", Objects.requireNonNullElse(route.route_color, "000000"));
                    userData.put("type", route.route_type);
                    wgsGeometry.setUserData(userData);
                    shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
                }
                for (Stop stop : feed.stops.values()) {
                    // This is inefficient, just bin points into mercator tiles.
                    Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
                    stopsIndex.insert(stopEnvelope, stop);
                }
                shapesIndex.build(); // can't index any more feeds after this.
                stopsIndex.build();
                indexedGtfs.add(bundleScopedId);
            }
        }

        final int tileExtent = 4096; // Standard is 4096, smaller can in theory make tiles more compact
        Envelope wgsEnvelope = MapTile.wgsEnvelope(zTile, xTile, yTile);
        Collection<Geometry> patternGeoms = new ArrayList<>(64);
        for (LineString wgsGeometry : (List<LineString>) shapesIndex.query(wgsEnvelope)) {
            Geometry tileGeometry = clipScaleAndSimplify(wgsGeometry, wgsEnvelope, tileExtent);
            if (tileGeometry != null) {
                tileGeometry.setUserData(wgsGeometry.getUserData());
                patternGeoms.add(tileGeometry);
            }
        }
        JtsLayer patternLayer = new JtsLayer("gtfs-pattern-shapes", patternGeoms, tileExtent);

        Collection<Geometry> stopGeoms = new ArrayList<>(64);
        for (Stop stop : ((List<Stop>) stopsIndex.query(wgsEnvelope))) {
            Coordinate wgsStopCoord = new Coordinate(stop.stop_lon, stop.stop_lat);
            if (!wgsEnvelope.contains(wgsStopCoord)) {
                continue;
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
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
        JtsLayer stopsLayer = new JtsLayer("gtfs-stops", stopGeoms, tileExtent);

        // Combine these two layers in a tile
        JtsMvt mvt = new JtsMvt(patternLayer, stopsLayer);
        MvtLayerParams mvtLayerParams = new MvtLayerParams(256, tileExtent);
        byte[] pbfMessage = MvtEncoder.encode(mvt, mvtLayerParams, new UserDataKeyValueMapConverter());

        return pbfMessage;
    }


    // Convert from WGS84 to integer intra-tile coordinates, eliminating points outside the envelope
    // and reducing number of points to keep tile size down.
    private static Geometry clipScaleAndSimplify (LineString wgsGeometry, Envelope wgsEnvelope, int tileExtent) {
        CoordinateSequence wgsCoordinates = wgsGeometry.getCoordinateSequence();
        boolean[] coordInsideEnvelope = new boolean[wgsCoordinates.size()];
        for (int c = 0; c < wgsCoordinates.size(); c += 1) {
            coordInsideEnvelope[c] = wgsEnvelope.contains(wgsCoordinates.getCoordinate(c));
        }
        List<Coordinate> tileCoordinates = new ArrayList<>(wgsCoordinates.size());
        for (int c = 0; c < wgsCoordinates.size(); c += 1) {
            boolean prevInside = (c > 0) ? coordInsideEnvelope[c-1] : false;
            boolean nextInside = (c < coordInsideEnvelope.length - 1) ? coordInsideEnvelope[c+1] : false;
            boolean thisInside = coordInsideEnvelope[c];
            if (thisInside || prevInside || nextInside) {
                Coordinate coord = wgsCoordinates.getCoordinateCopy(c);
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
            simplifier.setDistanceTolerance(1);
            Geometry simplifiedTileGeometry = simplifier.getResultGeometry();
            return simplifiedTileGeometry;
        } else {
            return null;
        }
    }
}