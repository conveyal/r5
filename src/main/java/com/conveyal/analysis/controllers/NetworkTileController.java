package com.conveyal.analysis.controllers;

import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.analysis.util.MapTile;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.google.common.collect.ImmutableMap;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtEncoder;
import com.wdtinc.mapbox_vector_tile.adapt.jts.UserDataKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.util.HttpStatus.OK_200;
import static com.conveyal.analysis.util.HttpUtils.CACHE_CONTROL_IMMUTABLE;
import static com.conveyal.r5.common.GeometryUtils.floatingWgsEnvelopeToFixed;
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
public class NetworkTileController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkTileController.class);

    private final TransportNetworkCache transportNetworkCache;

    public NetworkTileController (TransportNetworkCache transportNetworkCache) {
        this.transportNetworkCache = transportNetworkCache;
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        // Ideally we'd have region, project, bundle, and worker version parameters.
        // Those could be path parameters, x-conveyal-headers, etc.
        // sparkService.get("/api/bundles/:gtfsId/vectorTiles/:x/:y/:z", this::getTile);
        // Should end in .mvt but not clear how to do that in Sparkframework.
        //         sparkService.get("/:bundleId/:modificationNonceDigest/tiles", this::buildIndex, JsonUtil.toJson);
        sparkService.get("/:bundleId/tiles", this::buildIndex, JsonUtil.toJson);
        sparkService.get("/:bundleId/tiles/:z/:x/:y", this::getTile);
    }

    private TransportNetwork getNetworkFromRequest (Request request) {
        // "bundleId" is also the "graphId" in an `AnalysisWorkerTask`
        final String bundleId = request.params("bundleId");
        // final String modificationNonceDigest = request.params("modificationNonceDigest");
        checkNotNull(bundleId);
        TransportNetwork network = transportNetworkCache.getNetwork(bundleId);
        // "61fe817974f6230b0363aae1-8c07ddd4f8bd29ac10a4a109dd27d7b58dabd56c" // elevation only
        final String scenarioId = "61fe817974f6230b0363aae1-c38397b6249e9fa894ef39d667edbc3d9036c15b"; // elevation and sun
        network = transportNetworkCache.getNetworkForScenario(bundleId, scenarioId);
        checkNotNull(network);
        return network;
    }

    /**
     * Handler for building the index separately from retrieving tiles.
     * FIXME this is long-polling? Not building an index anymore. Maybe assert or check that Network has an index.
     */
    private Object buildIndex (Request request, Response response) {
        getNetworkFromRequest(request);
        response.status(OK_200);
        return ImmutableMap.of("message", "Network is ready.");
    }

    /**
     * We have tried offsetting the edges for opposite directions using org.geotools.geometry.jts.OffsetCurveBuilder
     * but this will offset in tile units and cause jumps at different zoom levels when rendered. Using offset styles
     * in MapboxGL with exponential interpolation by zoom level seems to work better for showing opposite directions
     * at high zoom levels. Alternatively we could just include one geometry per edge pair and report only one value
     * per pair. For example, for elevation data this could be the direction with the largest value which should always
     * be >= 1.
     */
    private Object getTile (Request request, Response response) {
        TransportNetwork network = getNetworkFromRequest(request);

        final long startTimeMs = System.currentTimeMillis();
        final int zTile = Integer.parseInt(request.params("z"));
        final int xTile = Integer.parseInt(request.params("x"));
        final int yTile = Integer.parseInt(request.params("y"));
        final int tileExtent = 4096; // Standard is 4096, smaller can in theory make tiles more compact

        Envelope wgsEnvelope = MapTile.wgsEnvelope(zTile, xTile, yTile);
        Collection<Geometry> edgeGeoms = new ArrayList<>(64);

        TIntSet edges = network.streetLayer.spatialIndex.query(floatingWgsEnvelopeToFixed(wgsEnvelope));
        edges.forEach(e -> {
            EdgeStore.Edge edge = network.streetLayer.edgeStore.getCursor(e);
            Geometry edgeGeometry = edge.getGeometry();
            edgeGeometry.setUserData(edge.attributesForDisplay());
            edgeGeoms.add(clipScaleAndSimplify(edgeGeometry, wgsEnvelope, tileExtent));
            // The index contains only forward edges in each pair. Also include the backward edges.
            // TODO factor out repetitive code?
            edge.advance();
            edgeGeometry = edge.getGeometry();
            edgeGeometry.setUserData(edge.attributesForDisplay());
            edgeGeoms.add(clipScaleAndSimplify(edgeGeometry, wgsEnvelope, tileExtent));
            return true;
        });

        response.header("Content-Type", "application/vnd.mapbox-vector-tile");
        response.header("Content-Encoding", "gzip");
        response.header("Cache-Control", CACHE_CONTROL_IMMUTABLE);
        response.status(OK_200);
        if (edgeGeoms.size() > 0) {
            JtsLayer edgeLayer = new JtsLayer("conveyal:osm:edges", edgeGeoms, tileExtent);
            JtsMvt mvt = new JtsMvt(edgeLayer);
            MvtLayerParams mvtLayerParams = new MvtLayerParams(256, tileExtent);
            byte[] pbfMessage = MvtEncoder.encode(mvt, mvtLayerParams, new UserDataKeyValueMapConverter());

            LOG.info("getTile({}, {}, {}, {}) in {}", network.scenarioId, zTile, xTile, yTile, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));

            return pbfMessage;
        } else {
            return new byte[]{};
        }
    }

    // Convert from WGS84 to integer intra-tile coordinates, eliminating points outside the envelope
    // and reducing number of points to keep tile size down.
    private static Geometry clipScaleAndSimplify (Geometry wgsGeometry, Envelope wgsEnvelope, int tileExtent) {
        CoordinateSequence wgsCoordinates = ((LineString) wgsGeometry).getCoordinateSequence();
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
            // TODO try higher tolerances, these are in tile pixels which are minuscule at 1/4096 of a tile
            simplifier.setDistanceTolerance(5);
            Geometry simplifiedTileGeometry = simplifier.getResultGeometry();
            simplifiedTileGeometry.setUserData(wgsGeometry.getUserData());
            return simplifiedTileGeometry;
        } else {
            return null;
        }
    }

}