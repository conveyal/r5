package com.conveyal.r5.analyst;

import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.Collection;

/**
 * References to all the data needed for a single analysis (single point or eventually regional).
 * The main objects that are voluminous and slow to construct are the network itself, and the egress cost tables.
 * This also serves as a compound return type for the NetworkPreloader. This allows all lazy-loading / building to be
 * performed in advance, and guarantees this whole set of objects will not be garbage collected, and are all reachable
 * from a single root reference that can be passed around.
 *
 * Alternatively we could put the linkedPointSets in a transient field of TransportNetwork, effectively merging this
 * class into TransportNetwork.
 *
 * Created by abyrd on 2019-08-22
 */
public class PreloadedNetwork {

    public final TransportNetwork transportNetwork;

    public final Collection<LinkedPointSet> linkedPointSets;

    // We could also have fields for PointSets and EgressCostTables, but those are easily reachable from
    // LinkedPointSets, and their relationships to the LinkedPointSet are preserved by following those references.
    // We have one PointSet, which has one LinkedPointSet per street mode, each of which can have one EgressCostTable.

    public PreloadedNetwork (TransportNetwork transportNetwork, Collection<LinkedPointSet> linkedPointSets) {
        this.transportNetwork = transportNetwork;
        this.linkedPointSets = linkedPointSets;
    }

}
