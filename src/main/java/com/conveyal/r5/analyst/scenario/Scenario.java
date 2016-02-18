package com.conveyal.r5.analyst.scenario;

import com.beust.jcommander.internal.Lists;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A scenario is an ordered sequence of modifications that will be applied non-destructively on top of a baseline graph.
 */
public class Scenario implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(Scenario.class);

    public final int id;

    public String description = "no description provided";

    /**
     * If non-null, only modifications whose activeInVariant list contain this string will be applied.
     * If null, all modifications will be applied.
     */
    public String useVariant;

    public List<Modification> modifications = Lists.newArrayList();

    public Scenario (@JsonProperty("id") int id) {
        this.id = id;
    }

    /**
     * @return a copy of the supplied network with the modifications in this scenario applied.
     */
    public TransportNetwork applyToTransportNetwork (TransportNetwork originalNetwork) {
        LOG.info("Applying scenario {}", this);
        useVariant = null;
        for (Modification modification : modifications) {
            if (modification.comment != null && modification.comment.startsWith("useVariant: ")) {
                // TODO replace this kludge to get the variant from comment
                useVariant = modification.comment.split(" ")[1];
            }
        }
        List<Modification> filteredModifications = modifications.stream()
                .filter(m -> m.isActiveInVariant(useVariant)).collect(Collectors.toList());
        LOG.info("Variant '{}' selected, with {} modifications active out of {} total.",
                useVariant, filteredModifications.size(), modifications.size());
        TransportNetwork network = originalNetwork.clone();
        LOG.info("Resolving modifications against TransportNetwork and sanity checking.");
        // FIXME actually we don't want to check all the parameters before applying any modifications.
        // Some parameters may become valid/invalid because of previous modifications.
        boolean errorsInScenario = false;
        for (Modification modification : filteredModifications) {
            boolean errorsInModification = modification.resolve(originalNetwork);
            if (errorsInModification) {
                errorsInScenario = true;
                LOG.error("Errors were detected in a scenario modification of type {}:", modification.getType());
                for (String warning : modification.warnings) {
                    LOG.error(warning);
                }
            }
        }
        // Each modification is applied in isolation, creating a new copy.
        // This is somewhat inefficient but easy to reason about.
        LOG.info("Applying modifications to TransportNetwork.");
        for (Modification modification : filteredModifications) {
            LOG.info("Applying modification of type {}", modification.getType());
            network = modification.applyToTransportNetwork(network);
        }
        return network;
    }

}
