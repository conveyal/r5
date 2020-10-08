package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Coordinate;

import java.util.EnumSet;

/**
 * Created by abyrd on 2020-05-12
 */
public class AddStreets extends Modification {

    /**
     * An array of linestrings, each of which represents a new routable edge in the graph.
     * For each linestring, an array of [lon, lat] points is provided.
     * Only the first and last points in each linestring will be connected to the street layer from the baseline
     * network. Linestrings will not be connected to each other, but may be connected to edge fragments created by
     * splitting baseline street edges to connect other linestrings.
     */
    public double[][][] lineStrings;

    public EnumSet<StreetMode> allowedModes;

    /**
     * Nonzero positive float km/h. TODO decide if this is post- or pre-congestion, adjust sortOrder.
     */
    public Double carSpeedKph;

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
     * Whether or not the created edges can be connected directly to origin and destination points.
     * Typically this would be true for a surface street but set to false for bridges and tunnels.
     */
    public boolean linkable;

    // Internal private fields used while applying the modification.

    /**
     * We defer spatial indexing until all linestrings have been processed, sidestepping concerns about linestring order
     * affecting linking. After all modifications are applied, it would be possible to index all the edges at once by
     * simply iterating from the first mutable index in steps of two. But we need to index the split edges as soon as
     * they're made to allow re-splitting.
     */
    private TIntList forwardEdgesToIndex = new TIntArrayList();

    @Override
    public boolean resolve (TransportNetwork network) {
        if (allowedModes == null || allowedModes.isEmpty()) {
            errors.add("You must supply at least one StreetMode.");
        }
        if (allowedModes != null && allowedModes.contains(StreetMode.CAR)) {
            if (carSpeedKph == null) {
                errors.add("You must supply a car speed when specifying the CAR mode.");
            }
        }
        if (carSpeedKph != null) {
            // TODO factor out repetitive range checking code into a utility function
            if (carSpeedKph < 1 || carSpeedKph > 150) {
                errors.add("Car speed should be in the range [1...150] kph. Specified speed is: " + carSpeedKph);
            }
        }
        if (lineStrings == null || lineStrings.length < 1) {
            errors.add("Modification contained no LineStrings.");
        } else for (double[][] lineString : lineStrings) {
            if (lineString.length < 2) {
                errors.add("LineString had less than two coordinates.");
            }
            for (double[] coord : lineString) {
                // Incoming coordinates use the same (x, y) axis order as JTS.
                Coordinate jtsCoord = new Coordinate(coord[0], coord[1]);
                rangeCheckCoordinate(jtsCoord, network);
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
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // FIXME linking only looks for one streetMode, but we can have multiple allowed modes
        StreetMode linkMode = allowedModes.stream().findFirst().get();
        for (double[][] lineString : lineStrings) {
            VertexStore.Vertex prevVertex = network.streetLayer.vertexStore.getCursor();
            VertexStore.Vertex currVertex = network.streetLayer.vertexStore.getCursor();
            for (int c = 0; c < lineString.length; c++) {
                double lon = lineString[c][0];
                double lat = lineString[c][1];
                if (c == 0) {
                    // Only first and last coordinates should be linked to the network.
                    int startLinkVertex = network.streetLayer.getOrCreateVertexNear(lat, lon, linkMode);
                    if (startLinkVertex < 0) {
                        errors.add(String.format("Failed to connect first vertex to streets at (%f, %f)", lat, lon));
                    }
                    prevVertex.seek(startLinkVertex);
                }
                // Only first and last vertices are linked to streets, the rest are only created as detached vertices.
                // Note, this can create zero-lenth edges when the start and/or end points are exactly on a street.
                int newDetachedVertex = network.streetLayer.vertexStore.addVertex(lat, lon);
                currVertex.seek(newDetachedVertex);
                createEdgePair(prevVertex, currVertex, network.streetLayer.edgeStore);
                prevVertex.seek(newDetachedVertex);
                if (c == lineString.length - 1) {
                    // Only first and last coordinates should be linked to the network.
                    int endLinkVertex = network.streetLayer.getOrCreateVertexNear(lat, lon, linkMode);
                    if (endLinkVertex < 0) {
                        errors.add(String.format("Failed to connect last vertex to streets at (%f, %f)", lat, lon));
                    }
                    currVertex.seek(endLinkVertex);
                    createEdgePair(prevVertex, currVertex, network.streetLayer.edgeStore);
                }
            }
        }
        // By convention we only index the forward edge since both edges in the pair share a geometry.
        // We may only want to index edges that have the LINKABLE flag, to avoid later filtering.
        EdgeStore.Edge forwardEdge = network.streetLayer.edgeStore.getCursor();
        for (int forwardEdgeNumber : forwardEdgesToIndex.toArray()) {
            forwardEdge.seek(forwardEdgeNumber);
            network.streetLayer.indexTemporaryEdgePair(forwardEdge);
        }
        return errors.size() > 0;
    }

    private void createEdgePair (VertexStore.Vertex previousVertex, VertexStore.Vertex currentVertex, EdgeStore edgeStore) {
        // After we have examined one previous vertex, start chaining them together with edges.
        // TODO factor out common edge creation logic for use here and in network building.
        double edgeLengthMm = 1000 * GeometryUtils.distance(
                previousVertex.getLat(),
                previousVertex.getLon(),
                currentVertex.getLat(),
                currentVertex.getLon()
        );
        if (edgeLengthMm > Integer.MAX_VALUE) {
            errors.add("Edge length in millimeters would overflow an int (was longer than 2147km).");
            edgeLengthMm = Integer.MAX_VALUE;
        }
        // TODO mono- or bidirectional
        EdgeStore.Edge newEdge = edgeStore.addStreetPair(
                previousVertex.index,
                currentVertex.index,
                (int) edgeLengthMm,
                -1
        );
        // We now have two new edges on which we need to set speed, mode flags, and time factors.
        forwardEdgesToIndex.add(newEdge.getEdgeIndex());
        // Forward edge
        handleNewEdge(newEdge);
        // Backward edge
        newEdge.advance();
        handleNewEdge(newEdge);
    }

    private void handleNewEdge (EdgeStore.Edge edge) {
        edge.allowStreetModes(allowedModes);
        edge.setSpeedKph(carSpeedKph);
        if (linkable) {
            edge.setFlag(EdgeStore.EdgeFlag.LINKABLE);
        } else {
            edge.clearFlag(EdgeStore.EdgeFlag.LINKABLE);
        }
        if (edge.getEdgeStore().edgeTraversalTimes != null) {
            if (walkTimeFactor != null) {
                edge.setWalkTimeFactor(walkTimeFactor);
            }
            if (bikeTimeFactor != null) {
                edge.setBikeTimeFactor(bikeTimeFactor);
            }
        }
    }

    @Override
    public boolean affectsStreetLayer () {
        // This modification directly changes and extends the StreetLayer.
        return true;
    }

    @Override
    public boolean affectsTransitLayer () {
        // No changes are made directly to the TransitLayer,
        // but new streets may require relinking and affect distance tables.
        return true;
    }

    @Override
    public int getSortOrder () {
        // AddStreets modifications are applied after ModifyStreets modifications, so their characteristics are not
        // modified (e.g. the car speed specified for an added street will not be scaled by the speed scaling factor
        // in a ModifyStreets modification that contains it.)
        // This also allows removing streets and then replacing them within a single scenario.
        // TODO decide if car speed supplied for new roads is post- or pre-congestion, adjust sortOrder accordingly.
        return 6;
    }

}
