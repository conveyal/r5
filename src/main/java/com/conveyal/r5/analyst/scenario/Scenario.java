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

    private static final boolean VERIFY_BASE_NETWORK_UNCHANGED = true;

    /**
     * @return a copy of the supplied network with the modifications in this scenario non-destructively applied.
     */
    public TransportNetwork applyToTransportNetwork (TransportNetwork originalNetwork) {
        LOG.info("Applying scenario {}", this);
        long baseNetworkChecksum = 0;
        if (VERIFY_BASE_NETWORK_UNCHANGED) {
            baseNetworkChecksum = originalNetwork.checksum();
        }
        useVariant = null;
        List<Modification> filteredModifications = modifications.stream()
                .filter(m -> m.isActiveInVariant(useVariant)).collect(Collectors.toList());
        LOG.info("Variant '{}' selected, with {} modifications active out of {} total.",
                useVariant, filteredModifications.size(), modifications.size());
        TransportNetwork copiedNetwork = originalNetwork.scenarioCopy();
        LOG.info("Resolving modifications against TransportNetwork and sanity checking.");
        // Check all the parameters before applying any modifications.
        // FIXME might some parameters may become valid/invalid because of previous modifications in the list?
        boolean errorsInScenario = false;
        for (Modification modification : filteredModifications) {
            boolean errorsInModification = modification.resolve(copiedNetwork);
            if (errorsInModification) {
                LOG.error("Errors were detected in a scenario modification of type {}:", modification.getType());
                for (String warning : modification.warnings) {
                    LOG.error(warning);
                }
                errorsInScenario = true;
            }
        }
        if (errorsInScenario) {
            throw new RuntimeException("Errors were found in the Scenario, bailing out.");
        }
        // Apply each modification in turn to the same extensible copy of the TransitNetwork.
        LOG.info("Applying modifications to TransportNetwork.");
        for (Modification modification : filteredModifications) {
            LOG.info("Applying modification of type {}", modification.getType());
            boolean errors = modification.apply(copiedNetwork);
            if (errors) {
                LOG.error("Error while applying modification {}", modification);
                throw new RuntimeException("Errors occured while applying the Scenario to the TransportNetwork, bailing out.");
            }
        }
        // FIXME can we do this once after all modifications are applied, or do we need to do it after every mod?
        copiedNetwork.transitLayer.rebuildTransientIndexes();
        if (VERIFY_BASE_NETWORK_UNCHANGED) {
            if (originalNetwork.checksum() != baseNetworkChecksum) {
                LOG.error("Applying a scenario mutated the base transportation network. THIS IS A BUG.");
            } else {
                LOG.info("Applying the scenario left the base transport network unchanged with high probability.");
            }
        }
        return copiedNetwork;
    }

}
