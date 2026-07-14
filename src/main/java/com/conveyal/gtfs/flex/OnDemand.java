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

    // Time windows are in seconds after midnight. Following the literature on flexible transit we
    // refer to the moment the rider is available to board (the end of any access walk) as the
    // "ready time". We check the pick-up window against this ready time plus the wait (delay)
    // defined for the service, awaiting the beginning of the window if that is later.
    // For the drop-off window, only the end of the window is stored. An operator who picked a
    // rider up will presumably drop them off even if they arrive before the published drop-off
    // window's start, as intentionally delaying arrival would serve no purpose.
    // A service that is always available (derived from GTFS lacking windows, or eventually a pick
    // up delay modification) is represented with a start time of 0 and an end time of MAX_VALUE.
    public int fromWindowStart;
    public int fromWindowEnd;
    public int toWindowEnd;

    /// Inexpensively pre-filters OnDemand services, deliberately overselecting. The final test for
    /// whether the service will be used is applied to each initial state as the on-demand street
    /// search is initialized. Returns true when this OnDemand service may be usable for a rider
    /// who will board in the interval `[beginTime, endTime)`. The tightest conveniently available
    /// bounds are the beginning of the departure time window at the origin, and the end of the
    /// departure time window at the origin plus the maximum travel time for the whole trip.
    public boolean canPickUpDuring (int beginTime, int endTime, BitSet serviceCodes) {
        if (!serviceCodes.get(this.serviceCode)) return false;
        double earliestBoarding = Math.max(beginTime, fromWindowStart) + durationOffset;
        return earliestBoarding < fromWindowEnd
                && earliestBoarding <= endTime
                && earliestBoarding < toWindowEnd;
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
    ///
    /// TODO explore whether this genuinely always allows access to on-demand stops
    /// Passing from drive to walk mode at the nearest road may assume the car road is also walkable.
    void findCarEdges (TransportNetwork network) {
        if (toStopIndexes == null) return;
        TIntSet carEdges = new TIntHashSet();
        VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor();
        for (int s : toStopIndexes) {
            int v = network.transitLayer.streetVertexForStop.get(s);
            if (v < 0) continue; // Stop was not linked to streets
            vertex.seek(v);
            // This split method is non-destructive.
            // These edges come from Split.find, which searches the spatial index containing
            // only the even (forward) edge of each pair.
            // TODO verify and document that even-edge behavior on all relevant method Javadoc.
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

