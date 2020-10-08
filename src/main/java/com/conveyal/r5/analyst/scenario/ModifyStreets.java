package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.Geometries;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.conveyal.r5.labeling.LevelOfTrafficStressLabeler.intToLts;

/**
 * This modification selects all edges inside a given set of polygons and changes their characteristics.
 */
public class ModifyStreets extends Modification {

    /**
     * A single multi-polygon, expressed as one array of [lat, lon] coordinates per polygon.
     * All edges entirely within the polygons will be selected.
     * Later, we could add filtering of selected edges, or selection of edges partially intersecting the polygons.
     * We could conceivably return the geometry of all the selected edges (up to a certain size) as an INFO level field
     * on the response.
     */
    public double[][][] polygons;

    /**
     * Overwrites the allowed modes for these streets with the given modes.
     * The empty set of allowed modes should be equivalent to removing the streets.
     */
    public EnumSet<StreetMode> allowedModes;

    /** Absolute car speed. Only one of carSpeedKph and carSpeedFactor may be provided, not both. */
    public Double carSpeedKph;

    /** Nonzero positive floating point multiplier on freeflow car speed. */
    public Double carSpeedFactor;

    /**
     * Nonzero positive float multiplier for the walking time to traverse the streets.
     * Values greater than one imply that it takes longer to traverse than a typical flat street link.
     * This can be seen variously as increasing distance, increase traversal time, or decreasing speed.
     * An increase in traversal time can be seen as more clock time or perceived time (generalized cost).
     * Values less than one imply it's faster (or more pleasant) to traverse, e.g. a slight downhill slope
     * with trees.
     * TODO in implementation REPLACE existing factors instead of scaling the existing factor.
     */
    public Double walkTimeFactor;

    /** Nonzero positive float traversal time multiplier, see walkTimeFactor. */
    public Double bikeTimeFactor;

    /**
     * Bike Level of Traffic Stress LTS: integer [1-4]
     * See documentation on BIKE_LTS_N constants in EdgeStore.
     */
    public Integer bikeLts;

    /**
     * After this modification is resolved against the TransportNetwork, this set will contain the index numbers
     * of all the edges that will be modified.
     */
    private TIntSet edgesInPolygon;

    @Override
    public boolean resolve (TransportNetwork network) {

        // Validate supplied polygon coordinates by loading them into JTS typed objects, ensuring they look valid.
        final GeometryFactory geometryFactory = Geometries.geometryFactory;
        List<Polygon> jtsPolygons = new ArrayList<>();
        if (polygons == null || polygons.length == 0) {
            errors.add("You must specify some polygons to select streets.");
            polygons = new double[][][]{};
        }
        for (double[][] polygon : polygons) {
            if (polygon.length < 3) {
                errors.add("Polygons must have at least three coordinates to enclose any space.");
                continue;
            }
            List<Coordinate> jtsCoordinates = new ArrayList<>();
            for (double[] coordinate : polygon) {
                if (coordinate.length != 2) {
                    errors.add("Each coordinate must have two values, a latitude and a longitude.");
                    continue;
                }
                Coordinate jtsCoordinate = new Coordinate(coordinate[0], coordinate[1]);
                rangeCheckCoordinate(jtsCoordinate, network);
                jtsCoordinates.add(jtsCoordinate);
            }
            jtsPolygons.add(geometryFactory.createPolygon(jtsCoordinates.toArray(new Coordinate[]{})));
        }
        MultiPolygon multiPolygon = geometryFactory.createMultiPolygon(jtsPolygons.toArray(new Polygon[]{}));

        // Find all edges inside this multipolygon / intersecting this multipolygon
        // network.streetLayer is already a protective copy made by method Scenario.applyToTransportNetwork,
        // and network.streetLayer.edgeStore is already an extend-only copy.
        // TODO convert to fixed point
        edgesInPolygon = new TIntHashSet();
        Envelope fixedEnvelope = GeometryUtils.floatingWgsEnvelopeToFixed(multiPolygon.getEnvelopeInternal());
        TIntSet candidateEdges = network.streetLayer.findEdgesInEnvelope(fixedEnvelope);
        EdgeStore.Edge edge = network.streetLayer.edgeStore.getCursor();
        candidateEdges.forEach(e -> {
            edge.seek(e);
            Geometry edgeGeometryFloating = edge.getGeometry();
            if (multiPolygon.intersects(edgeGeometryFloating)) {
                // Mark for modification, which actually means marking it deleted and then making a new one.
                edgesInPolygon.add(e);
            }
            return true;
        });
        info.add(String.format("Will affect %d edges out of %d candidates.", edgesInPolygon.size(),
                candidateEdges.size()));

        // Range check and otherwise validate numeric parameters

        if (carSpeedKph != null && carSpeedFactor != null) {
            errors.add("You must specify only one of carSpeedKph or carSpeedFactor.");
        }
        if (carSpeedKph != null) {
            if (carSpeedKph <= 0 || carSpeedKph > 130) {
                errors.add("Car speed must be in the range (0...130] kph.");
            }
        }
        if (carSpeedFactor != null) {
            if (carSpeedFactor <= 0 || carSpeedFactor > 10) {
                errors.add("Car speed factor must be in the range (0...10].");
            }
        }
        if (walkTimeFactor != null) {
            if (network.streetLayer.edgeStore.edgeTraversalTimes == null && walkTimeFactor != 1) {
                errors.add("walkGenCostFactor can only be set to values other than 1 on networks that support per-edge factors.");
            }
            if (walkTimeFactor <= 0 || walkTimeFactor > 10) {
                errors.add("walkGenCostFactor must be in the range (0...10].");
            }
        }
        if (bikeTimeFactor != null) {
            if (network.streetLayer.edgeStore.edgeTraversalTimes == null && bikeTimeFactor != 1) {
                errors.add("bikeGenCostFactor can only be set to values other than 1 on networks that support per-edge factors.");
            }
            if (bikeTimeFactor <= 0 || bikeTimeFactor > 10) {
                errors.add("bikeGenCostFactor must be in the range (0...10].");
            }
        }
        if (bikeLts != null) {
            if (bikeLts < 0 || bikeLts > 4) {
                errors.add("bikeLts must be in the range [0...4].");
            }
        }
        if (allowedModes == null) {
            errors.add("You must specify a list of allowedModes, which may be empty.");
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        EdgeStore edgeStore = network.streetLayer.edgeStore;
        EdgeStore.Edge oldEdge = edgeStore.getCursor();
        // By convention we only index the forward edge in each pair, so we're iterating over forward edges here.
        for (TIntIterator edgeIterator = edgesInPolygon.iterator(); edgeIterator.hasNext(); ) {
            int oldForwardEdge = edgeIterator.next();
            int oldBackwardEdge = oldForwardEdge + 1;
            oldEdge.seek(oldForwardEdge);
            // Our scenario EdgeStore cannot change existing edges, only delete them and add new ones.
            // So first we mark all affected edges as deleted, then recreate them with new characteristics.
            edgeStore.temporarilyDeletedEdges.add(oldForwardEdge);
            edgeStore.temporarilyDeletedEdges.add(oldBackwardEdge);
            // TODO pull out replicateEdge method.
            EdgeStore.Edge newEdge = edgeStore.addStreetPair(
                    oldEdge.getFromVertex(),
                    oldEdge.getToVertex(),
                    oldEdge.getLengthMm(),
                    oldEdge.getOSMID()
            );
            newEdge.copyPairFlagsAndSpeeds(oldEdge);
            newEdge.copyPairGeometry(oldEdge);
            // Index the new scenario edge pair after geometry is set.
            // By convention we only index the forward edge since both edges have the same geometry.
            network.streetLayer.indexTemporaryEdgePair(newEdge);

            // First copy and set characteristics of the forward edge in the pair
            handleOneEdge(newEdge, oldEdge);

            // Then copy and set characteristics of the backward edge in the pair
            newEdge.advance();
            oldEdge.advance();
            handleOneEdge(newEdge, oldEdge);
        }
        // Instead of repeating this error logic in every resolve and apply method,
        // we should really be doing this using a modification.hasErrors() method from one frame up.
        return errors.size() > 0;
    }

    @JsonIgnore
    private void handleOneEdge (EdgeStore.Edge newEdge, EdgeStore.Edge oldEdge) {
        newEdge.disallowAllModes();
        newEdge.allowStreetModes(allowedModes);
        if (bikeLts != null) {
            // Overwrite the LTS copied in the flags
            newEdge.setFlag(intToLts(bikeLts));
        }
        if (carSpeedKph != null) {
            newEdge.setSpeedKph(carSpeedKph);
        } else if (carSpeedFactor != null) {
            newEdge.setSpeedKph(oldEdge.getSpeedKph() * carSpeedFactor);
        }
        if (newEdge.getEdgeStore().edgeTraversalTimes != null) {
            newEdge.getEdgeStore().edgeTraversalTimes.copyTimes(
                    oldEdge.getEdgeIndex(),
                    newEdge.getEdgeIndex(),
                    walkTimeFactor,
                    bikeTimeFactor
            );
        }
    }

    @Override
    public boolean affectsStreetLayer () {
        return true;
    }

    @Override
    public boolean affectsTransitLayer () {
        return true;
    }

    @Override
    public int getSortOrder () {
        return 5;
    }

}
