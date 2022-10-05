package com.conveyal.analysis.controllers;

import com.conveyal.components.HttpController;
import com.conveyal.r5.streets.Edge;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.util.HttpUtils;
import com.conveyal.util.VectorMapTile;
import com.conveyal.worker.TransportNetworkCache;
import com.google.common.collect.ImmutableMap;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.conveyal.util.GeometryUtils.floatingWgsEnvelopeToFixed;
import static com.conveyal.util.HttpStatus.OK_200;
import static com.conveyal.util.HttpUtils.CACHE_CONTROL_IMMUTABLE;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Defines HTTP API endpoints to return Mapbox vector tiles of the street network. This is not simply the underlying
 * OSM data, but the street edges created from that OSM data. This allows the user to verify how the OSM tags have
 * been interpreted as mode permissions, speeds, etc. and eventually should allow visualizing the effects of scenarios
 * on the street network, including scenarios that change the characteristics of street edges or create new streets.
 * See GtfsVectorTileMaker for more information on the vector tile spec and tile numbers.
 */
public class NetworkTileController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkTileController.class);

    /** Vector tile layer name. This must match the layer name expected in the UI code. */
    private static final String EDGE_LAYER_NAME = "conveyal:osm:edges";

    /** The zoom level at which each StreetClass appears, indexed by StreetClass.code from 0...4. */
    private static final int[] zoomForStreetClass = new int[] {8, 10, 11, 12, 13};

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
        sparkService.get("/:bundleId/tiles", this::buildIndex, HttpUtils.toJson);
        sparkService.get("/:bundleId/tiles/:z/:x/:y", this::getEdgeGeometryVectorTile);
    }

    private TransportNetwork getNetworkFromRequest (Request request) {
        // "bundleId" is also the "graphId" in an `AnalysisWorkerTask`
        final String bundleId = request.params("bundleId");
        // final String modificationNonceDigest = request.params("modificationNonceDigest");
        checkNotNull(bundleId);
        TransportNetwork network = transportNetworkCache.getNetwork(bundleId);
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
     * Get all of the edge geometries and attributes within a given map tile's envelope, clipping them to that envelope
     * (plus a small buffer) and projecting their coordinates from WGS84 to intra-tile units.
     *
     * We have tried offsetting the edges for opposite directions using org.geotools.geometry.jts.OffsetCurveBuilder
     * but this will offset in tile units and cause jumps at different zoom levels when rendered. Using offset styles
     * in MapboxGL with exponential interpolation by zoom level seems to work better for showing opposite directions
     * at high zoom levels. Alternatively we could just include one geometry per edge pair and report only one value
     * per pair. For example, for elevation data this could be the direction with the largest value which should always
     * be >= 1.
     */
    private List<Geometry> getClippedAndProjectedEdgeGeometries (TransportNetwork network, VectorMapTile vectorMapTile) {
        List<Geometry> edgeGeoms = new ArrayList<>(64);

        TIntSet edges = network.streetLayer.spatialIndex.query(floatingWgsEnvelopeToFixed(vectorMapTile.envelope));
        edges.forEach(e -> {
            Edge edge = network.streetLayer.getEdgeCursor(e);
            // TODO at low zoom levels, include only edge pairs. At high, include different directions in pair.
            if (vectorMapTile.zoom < zoomForStreetClass[edge.getStreetClassCode()]) {
                return true; // Continue iteration.
            }
            Geometry edgeGeometry = vectorMapTile.clipScaleAndSimplify(edge.getGeometry());
            if (edgeGeometry != null) {
                edgeGeometry.setUserData(edge.attributesForDisplay());
                edgeGeoms.add(edgeGeometry);
            }
            // The index contains only forward edges in each pair. Also include the backward edges.
            // TODO factor out repetitive code?
            edge.advance();
            edgeGeometry = vectorMapTile.clipScaleAndSimplify(edge.getGeometry());
            if (edgeGeometry != null) {
                edgeGeometry.setUserData(edge.attributesForDisplay());
                edgeGeoms.add(edgeGeometry);
            }
            return true;
        });

        return edgeGeoms;
    }

    /**
     * Create a Mapbox Vector Tile (MVT) of a TransportNetwork's processed OSM Edges for the Z/X/Y tile numbers
     * given in the request URL parameters.
     */
    private Object getEdgeGeometryVectorTile(Request request, Response response) {
        final int zTile = Integer.parseInt(request.params("z"));
        final int xTile = Integer.parseInt(request.params("x"));
        final int yTile = Integer.parseInt(request.params("y"));

        TransportNetwork network = getNetworkFromRequest(request);
        VectorMapTile vectorMapTile = new VectorMapTile(zTile, xTile, yTile);

        final long startTimeMs = System.currentTimeMillis();

        response.header("Content-Type", "application/vnd.mapbox-vector-tile");
        response.header("Content-Encoding", "gzip");
        response.header("Cache-Control", CACHE_CONTROL_IMMUTABLE);
        response.status(OK_200);

        List<Geometry> edges = getClippedAndProjectedEdgeGeometries(network, vectorMapTile);
        if (edges.size() > 0) {
            byte[] pbfMessage = vectorMapTile.encodeLayersToBytes(
                    vectorMapTile.createLayer(EDGE_LAYER_NAME, edges)
            );
            LOG.debug("getTile({}, {}, {}, {}) in {}", network.scenarioId, zTile, xTile, yTile,
                    Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
            return pbfMessage;
        } else {
            return new byte[]{};
        }
    }

}