package com.conveyal.r5.analyst.scenario;

import com.beust.jcommander.internal.Lists;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.transit.TransferFinder;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * A scenario is an ordered sequence of modifications that will be applied non-destructively on top of a baseline graph.
 */
public class Scenario implements Serializable {
    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(Scenario.class);

    /**
     * If this ID is non null, this scenario should be identical to all others with the same ID. This enables us to
     * cache things like linked point sets and modified networks keyed on the scenario that has been applied.
     */
    public String id;

    public String description = "no description provided";

    public List<Modification> modifications = Lists.newArrayList();

    /** Map from feed ID to feed CRC32 to ensure that we can't apply scenarios to the wrong feeds */
    public Map<String, Long> feedChecksums;

    /** If this is true, expensive checks will be done to ensure that the original network was not damaged by the process. */
    private static final boolean VERIFY_BASE_NETWORK_UNCHANGED = false;

    /**
     * @return a copy of the supplied network with the modifications in this scenario non-destructively applied.
     */
    public TransportNetwork applyToTransportNetwork (TransportNetwork originalNetwork) {
        LOG.info("Applying scenario {}", this.id);

        // make sure this scenario is applicable to this network
        if (feedChecksums == null) {
            LOG.warn("Scenario does not have feed checksums, not checking to ensure it is applicable to transport network.");
        } else if (originalNetwork.transitLayer.feedChecksums == null || originalNetwork.transitLayer.feedChecksums.isEmpty()) {
            LOG.warn("Transit layer does not have feed checksums, not checking to ensure scenario is applicable.");
        } else {
            if (originalNetwork.transitLayer.feedChecksums.size() != feedChecksums.size()) {
                // is this warning necessary? modifications should apply cleanly if there are additional feeds in the
                // transport network.
                LOG.error("Number of feeds does not match in transport network and scenario: network has {}, scenario {}",
                        originalNetwork.transitLayer.feedChecksums.size(), feedChecksums.size());
                return null; // TODO throw exception?
            }

            for (Map.Entry<String, Long> e : feedChecksums.entrySet()) {
                long checksum = e.getValue();
                String feedId = e.getKey();

                if (!originalNetwork.transitLayer.feedChecksums.containsKey(feedId)) {
                    LOG.error("Scenario refers to feed {} not in base network", feedId);
                    throw new IllegalArgumentException("Scenario refers to feed not in base network");
                }

                long checksumInBaseNetwork = originalNetwork.transitLayer.feedChecksums.get(feedId);

                if (checksumInBaseNetwork != checksum) {
                    LOG.error("Checksum in base network for feed ID {} does not match scenario: base {}, scenario {}",
                            feedId, checksumInBaseNetwork, checksum);
                    throw new IllegalArgumentException("Checksums for feed do not match");
                }
            }

            LOG.info("All checksums match between scenario and base network.");
        }

        long baseNetworkChecksum = 0;
        // Put the modifications in canonical order before applying them.
        modifications.sort((a, b) -> a.getSortOrder() - b.getSortOrder());
        if (VERIFY_BASE_NETWORK_UNCHANGED) {
            baseNetworkChecksum = originalNetwork.checksum();
        }
        TransportNetwork copiedNetwork = originalNetwork.scenarioCopy(this);
        LOG.info("Resolving modifications against TransportNetwork and sanity checking.");
        // Check all the parameters before applying any modifications.
        // Might some parameters may become valid/invalid because of previous modifications in the list?
        List<Modification> modificationsWithErrors = new ArrayList<>();
        List<Modification> modificationsWithWarnings = new ArrayList<>();
        for (Modification modification : modifications) {
            boolean errorsInModification = modification.resolve(copiedNetwork);
            if (errorsInModification) {
                modificationsWithErrors.add(modification);
            }
            // warning caught after modification application, to avoid duplicates
        }
        // Throw one big exception containing any errors that were detected.
        if (!modificationsWithErrors.isEmpty()) {
            throw new ScenarioApplicationException(modificationsWithErrors);
        }
        // Apply each modification in turn to the same extensible copy of the TransitNetwork.
        LOG.info("Applying modifications to TransportNetwork.");
        for (Modification modification : modifications) {
            LOG.info("Applying modification of type {}", modification.getClass().getSimpleName());
            boolean errors = modification.apply(copiedNetwork);
            if (errors) {
                // Bail out at the first error, because modification application changes the underlying network and
                // could lead to meaningless errors on subsequent modifications.
                throw new ScenarioApplicationException(Arrays.asList(modification));
            }

            if (!modification.warnings.isEmpty()) {
                modificationsWithWarnings.add(modification);
            }
        }

        copiedNetwork.scenarioApplicationWarnings = modificationsWithWarnings.stream()
                .map(m -> new TaskError(m, m.warnings))
                .collect(Collectors.toList());

        // Is it OK that we do this once after all modifications are applied, or do we need to do it after every mod?
        copiedNetwork.transitLayer.rebuildTransientIndexes();

        // Rebuild edge lists to account for changes from scenario application
        copiedNetwork.streetLayer.buildEdgeLists();
        // TODO Elaborate on this
        // Rebuild distance tables affected by street network changes
        Geometry treeRebuildZone =
                copiedNetwork.streetLayer.scenarioEdgesBoundingGeometry(TransitLayer.WALK_DISTANCE_LIMIT_METERS);
        copiedNetwork.transitLayer.buildDistanceTables(treeRebuildZone);
        
        // Find the transfers originating at or terminating at new stops.
        // TODO also rebuild transfers which are near street network changes but which do not connect to new stops.
        new TransferFinder(copiedNetwork).findTransfers();

        // Any linkages to the new scenario street network will be built as needed based on the incoming request.
        // FIXME New routes drawn outside the original bounds cannot have an effect,
        // because the (unlinked) grid point set has been copied verbatim to the new network.

        if (VERIFY_BASE_NETWORK_UNCHANGED) {
            if (originalNetwork.checksum() != baseNetworkChecksum) {
                LOG.error("Applying a scenario mutated the base transportation network. THIS IS A BUG.");
            } else {
                LOG.info("Applying the scenario left the base transport network unchanged with high probability.");
            }
        }
        return copiedNetwork;
    }

    /**
     * @return true if applying this scenario will cause changes to the StreetLayer of a TransportNetwork.
     * This indicates whether a protective copy must be made of the StreetLayer, whether the resulting
     * modified TransportNetwork must be re-linked to destination pointsets or the original linkage can be re-used,
     * and whether transient indexes must be re-built on the StreetLayer.
     */
    public boolean affectsStreetLayer () {
        return modifications.stream().anyMatch(Modification::affectsStreetLayer);
    }

    /**
     * @return true if this scenario will result in changes to the TransitLayer of the TransportNetwork.
     * This determines whether it is necessary to make a protective copy of the TransitLayer, and whether transient
     * indexes, stop trees and transfers must be re-built on the TransitLayer.
     */
    public boolean affectsTransitLayer() {
        return modifications.stream().anyMatch(Modification::affectsTransitLayer);
    }

}
