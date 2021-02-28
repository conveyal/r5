package com.conveyal.analysis.controllers;

import com.conveyal.analysis.util.MapTile;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
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
import spark.Request;
import spark.Response;
import spark.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.conveyal.analysis.util.HttpStatus.OK_200;
import static com.google.common.base.Preconditions.checkNotNull;

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
public class GtfsTileController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsTileController.class);

    private final GTFSCache gtfsCache;

    // FIXME Super bad: one spatial index for all GTFS feeds. Ideally indexes could be shared with gtfs-api / gtfs-lib.
    //  Also consider indexing by rasterizing into high-zoom web mercator tiles as bins, instead of general purpose
    //  rectangle-tree. May also be integrated with efforts to convert general GraphQL API to few specialized endpoints.
    private final STRtree shapesIndex = new STRtree();

    private final STRtree stopsIndex = new STRtree();

    private final Set<String> indexedGtfs = new HashSet<>();

    public GtfsTileController (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        // Ideally we'd have region, project, bundle, and worker version parameters.
        // Those could be path parameters, x-conveyal-headers, etc.
        // sparkService.get("/api/bundles/:gtfsId/vectorTiles/:x/:y/:z", this::getTile);
        // Should end in .mvt but not clear how to do that in Sparkframework.
        sparkService.get("/vectorTiles/:gtfsId/:z/:x/:y", this::getTile);
    }

    private Object getTile (Request request, Response response) {
        final String gtfsId = request.params("gtfsId");
        final int zTile = Integer.parseInt(request.params("z"));
        final int xTile = Integer.parseInt(request.params("x"));
        final int yTile = Integer.parseInt(request.params("y"));

        checkNotNull(gtfsId);
        GTFSFeed feed = gtfsCache.get(gtfsId);
        checkNotNull(feed);

        // Ensure only one request lazy-indexes the gtfs shapes
        synchronized (this) {
            if (!indexedGtfs.contains(gtfsId)) {
                // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
                LOG.info("Indexing {} patterns", feed.patterns.size());
                for (Pattern pattern : feed.patterns.values()) {
                    String exemplarTripId = pattern.associatedTrips.get(0);
                    LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);
                    // shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), exemplarTripId);
                    shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
                }
                for (Stop stop : feed.stops.values()) {
                    // This is inefficient, just bin points into mercator tiles.
                    Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
                    stopsIndex.insert(stopEnvelope, stop);
                }
                shapesIndex.build(); // can't index any more feeds after this.
                stopsIndex.build();
                indexedGtfs.add(gtfsId);
            }
        }

        final int tileExtent = 4096; // Standard is 4096, smaller can in theory make tiles more compact

        Envelope wgsEnvelope = MapTile.wgsEnvelope(zTile, xTile, yTile);
        Collection<Geometry> patternGeoms = new ArrayList<>(64);
        for (LineString wgsGeometry : (List<LineString>) shapesIndex.query(wgsEnvelope)) {
            Geometry tileGeometry = clipScaleAndSimplify(wgsGeometry, wgsEnvelope, tileExtent);
            if (tileGeometry != null) {
                // TODO tag these with route IDs, names etc. and include transit stops with names.
                //  This will probably mean bypassing the JTS adapters and creating features individually.
                patternGeoms.add(tileGeometry);
            }
        }
        JtsLayer patternLayer = new JtsLayer("patternShapes", patternGeoms, tileExtent);

        Collection<Geometry> stopGeoms = new ArrayList<>(64);
        for (Stop stop : ((List<Stop>) stopsIndex.query(wgsEnvelope))) {
            Coordinate wgsStopCoord = new Coordinate(stop.stop_lon, stop.stop_lat);
            if (!wgsEnvelope.contains(wgsStopCoord)) {
                continue;
            }
            // Factor out this duplicate code from clipScaleAndSimplify
            Coordinate tileCoord = wgsStopCoord.copy();
            tileCoord.x = ((tileCoord.x - wgsEnvelope.getMinX()) * tileExtent) / wgsEnvelope.getWidth();
            tileCoord.y = ((wgsEnvelope.getMaxY() - tileCoord.y) * tileExtent) / wgsEnvelope.getHeight();
            stopGeoms.add(GeometryUtils.geometryFactory.createPoint(tileCoord));
        }
        JtsLayer stopsLayer = new JtsLayer("stops", stopGeoms, tileExtent);

        // Combine these two layers in a tile
        JtsMvt mvt = new JtsMvt(patternLayer, stopsLayer);
        MvtLayerParams mvtLayerParams = new MvtLayerParams(256, tileExtent);
        byte[] pbfMessage = MvtEncoder.encode(mvt, mvtLayerParams, new UserDataKeyValueMapConverter());

        response.header("Content-Type", "application/vnd.mapbox-vector-tile");
        response.header("Content-Encoding", "gzip");
        response.header("Cache-Control", "max-age=3600, immutable");
        response.status(OK_200);
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