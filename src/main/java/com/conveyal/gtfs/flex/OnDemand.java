package com.conveyal.gtfs.flex;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.BitSet;

/// Our internal TransportNetwork representation of an on-demand transit service.
/// Currently these connect a polygonal zone or set of pointlike stops to another such zone or set.
/// Instances of this class will be serialized into the TransportNetwork. Therefore it's probably
/// better to switch to our CPolygons or register custom serialization code in network serialization.
/// But all the JTS geometries will have been constructed from a single factory during network
/// alleviating the factory-instance-reference problem a bit.
public class OnDemand implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public String id;
    public String name;
    // The GeoTools Polygonal interface does not extend Geometry so doesn't have basic predicates
    // like "contains". This is something we need to improve upon in our own geometry types.
    public Polygon fromPolygon;
    public Polygon toPolygon;
    // TIntSet forces use of nonstandard bool lambda functions and final variables for iteration.
    // We demote them to arrays before use. These will be null if the service does not specify them.
    public int[] fromStopIndexes;
    public int[] toStopIndexes;
    public int[] toCarEdges;
    public String serviceId;
    public int serviceCode;
    public double durationOffset;
    public double durationFactor;
    // Time windows are in seconds after midnight.
    public int timeWindowStart;
    public int timeWindowEnd;

    public boolean canPickUpAt (int time, BitSet serviceCodes) {
        return serviceCodes.get(this.serviceCode) && time >= timeWindowStart && time < timeWindowEnd;
    }

    /// The service code is checked at pick-up, check only time at drop-off.
    public boolean canDropOffAt (int time) {
        return time >= timeWindowStart && time < timeWindowEnd;
    }

    /// For all scheduled transit uses, stops should always be reached via walkable edges. We do
    /// not need to determine the driving movement of the transit vehicles to roads, we just use
    /// the published times.
    ///
    /// But for on-demand services, we actually conduct a street search to estimate what route the
    /// service might use between various stops. This poses a problem as it requires our transit
    /// stop vertices to be reachable by walking as well as driving modes.
    ///
    /// Street-to-stop link edges are traversable by all modes, but the adjacent street edges are
    /// only guaranteed to be walkable, not driveable or bikable. In further work we may want to
    /// allow multiple linkage, potentially connecting a stop via separate edges to both walk and
    /// car roads. For now we will do mini-searches and store closest car edges to each stop.
    void findCarEdges (TransportNetwork network) {
        if (toStopIndexes == null) return;
        TIntSet carEdges = new TIntHashSet();
        VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor();
        for (int s : toStopIndexes) {
            int v = network.transitLayer.streetVertexForStop.get(s);
            if (v < 0) continue; // Stop was not linked to streets
            vertex.seek(v);
            // This split method is non-destructive.
            Split split = network.streetLayer.findSplit(vertex.getLat(), vertex.getLon(),
                  StreetLayer.INITIAL_LINK_RADIUS_METERS, StreetMode.CAR);
            if (split != null) {
                carEdges.add(split.edge);
            } else {
                LOG.warn("On-demand stop {} was not near any roads permitting CAR.",
                      network.transitLayer.stopIdForIndex.get(s));
            }
        }
        this.toCarEdges = carEdges.toArray();
    }

}

