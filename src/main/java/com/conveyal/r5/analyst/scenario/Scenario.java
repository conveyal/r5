package com.conveyal.r5.analyst.scenario;

import com.beust.jcommander.internal.Lists;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

/**
 * A scenario is an ordered sequence of modifications that will be applied non-destructively on top of a baseline graph.
 */
public class Scenario implements Serializable {

    public final int id;

    public String description = "no description provided";

    public List<Modification> modifications = Lists.newArrayList();

    public Scenario (@JsonProperty("id") int id) {
        this.id = id;
    }

    /**
     * @return a copy of the supplied network with the modifications in this scenario applied.
     */
    public TransportNetwork applyToTransportNetwork (TransportNetwork originalNetwork) {
        TransportNetwork network = originalNetwork.clone();
        // Each modification is applied in isolation, creating a new copy.
        // This is somewhat inefficient but easy to reason about.
        for (Modification modification : this.modifications) {
            network = modification.applyToTransportNetwork(network);
        }
        return network;
    }

}
