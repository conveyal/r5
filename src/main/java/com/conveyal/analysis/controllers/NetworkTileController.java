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

import static com.conveyal.analysis.controllers.GtfsVectorTileMaker.clipScaleAndSimplify;
import static com.conveyal.analysis.util.HttpStatus.OK_200;
import static com.conveyal.analysis.util.HttpUtils.CACHE_CONTROL_IMMUTABLE;
import static com.conveyal.r5.common.GeometryUtils.floatingWgsEnvelopeToFixed;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Defines HTTP API endpoints to return Mapbox vector tiles of the street network. This is not simply the underlying
 * OSM data, but the street edges created from that OSM data. This allows the user to verify how the OSM tags have
 * been interpreted as mode permissions, speeds, etc. and eventually should allow visualizing the effets of scenarios
 * on the street nework, includng scenarios that change the characteristics of street edges or create new streets.
 * See GtfsVectorTileMaker for more information on the vector tile spec and tile numbers.
 */
public class NetworkTileController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkTileController.class);

    /**
     * Complex street geometries will be simplified, but the geometry will not deviate from the original by more
     * than this many tile units. These are minuscule at 1/4096 of the tile width or height.
     */
    private static final int LINE_SIMPLIFY_TOLERANCE = 5;

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
        // final String scenarioId = "61fe817974f6230b0363aae1-c38397b6249e9fa894ef39d667edbc3d9036c15b"; // elevation and sun
        // network = transportNetworkCache.getNetworkForScenario(bundleId, scenarioId);
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
            // TODO at low zoom levels, include only edge pairs. At high, include different directions in pair.
            if (zTile < zoomForStreetClass[edge.getStreetClassCode()]) {
                return true; // Continue iteration.
            }
            LineString edgeGeometry = edge.getGeometry();
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

}