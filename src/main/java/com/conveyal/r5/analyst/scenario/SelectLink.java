package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.r5.analyst.cluster.SelectedLink;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.r5.common.GeometryUtils.envelopeForCircle;
import static com.conveyal.r5.common.GeometryUtils.polygonForEnvelope;
import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * This custom Modification restricts CSV path output to only include transit passing through a specified rectangle.
 * This allows cutting down the size of the output considerably, consolidating results in a way that's useful for some
 * network assignment and congestion problems. The parameters lon, lat, and radiusMeters define a selection box.
 * @see SelectedLink
 */
public class SelectLink extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Public fields supplied on the custom modification.

    public double lon;

    public double lat;

    public double radiusMeters;

    /// Private derived fields used in subsequent calculations.

    private Polygon boxPolygon;

    private Map<String, GTFSFeed> feedForUnscopedId;

    private int nPatternsWithoutShapes = 0;

    private int nPatternsWithoutGtfs = 0;

    @Override
    public boolean resolve(TransportNetwork network) {
        // Convert the incoming description of the selected link area to a Geometry for computing intersections.
        boxPolygon = polygonForEnvelope(envelopeForCircle(lon, lat, radiusMeters));
        // Iterate over all TripPatterns in the TransitLayer and ensure that we can associate a feed with each one.
        // These feeds must have been previously supplied with the injectGtfs() method. The feed IDs recorded in the
        // TripPatterns are not bundle-scoped. Check that a feed with a correctly de-scoped ID was supplied for every
        // TripPattern in this TransitLayer. Note that this only applies to patterns in the base network - other
        // modifications in the scenario such as add-trips can create new patterns that don't reference any GTFS.
        for (TripPattern tripPattern : network.transitLayer.tripPatterns) {
            String feedId = feedIdForTripPattern(tripPattern);
            if (isNullOrEmpty(feedId)) {
                addError("Could not find feed ID prefix in route ID " + tripPattern.routeId);
                continue;
            }
            GTFSFeed feed = feedForUnscopedId.get(feedId);
            if (feed == null) {
                addError("Could not find feed for ID " + feedId);
            }
        }
        return hasErrors();
    }

    @Override
    public boolean apply(TransportNetwork network) {
        // This method is basically serving as a factory method for a SelectedLink instance. Those instances are
        // immutable, so need some kind of external factory or builder to construct them incrementally.
        TIntObjectMap<int[]> hopsInTripPattern = new TIntObjectHashMap<>();

        // During raptor search, paths are recorded in terms of pattern and stop index numbers rather than
        // TripPattern and Stop instance references, so iterate over the numbers.
        final List<TripPattern> patterns = network.transitLayer.tripPatterns;
        for (int patternIndex = 0; patternIndex < patterns.size(); patternIndex++) {
            TripPattern tripPattern = patterns.get(patternIndex);
            // Make a sacrificial protective copy to avoid interfering with other requests using this network.
            // We're going to add shape data to this TripPattern then throw it away immediately afterward.
            // Be careful not to use a reference to this clone as a key in any Maps, it will not match TransitLayer.
            tripPattern = tripPattern.clone();
            String feedId = feedIdForTripPattern(tripPattern);
            GTFSFeed feed = feedForUnscopedId.get(feedId);
            if (feed == null) {
                // We could not find any feed ID on this pattern, or the apparent feed ID does not match any known feed.
                // Since feeds for all patterns were all verified in apply(), this means the pattern must have been
                // added by another modification in the scenario.
                nPatternsWithoutGtfs += 1;
            } else {
                TransitLayer.addShapeToTripPattern(feed, tripPattern);
            }
            if (tripPattern.shape == null) {
                nPatternsWithoutShapes += 1;
            }
            // TransitLayer parameter enables fetching straight lines between stops in case shapes are not present.
            List<LineString> hopGeometries = tripPattern.getHopGeometries(network.transitLayer);
            TIntArrayList intersectedHops = new TIntArrayList();
            for (int hop = 0; hop < hopGeometries.size(); hop++) {
                LineString hopGeometry = hopGeometries.get(hop);
                if (boxPolygon.intersects(hopGeometry)) {
                    intersectedHops.add(hop);
                }
            }
            if (!intersectedHops.isEmpty()) {
                hopsInTripPattern.put(patternIndex, intersectedHops.toArray());
            }
        }
        if (nPatternsWithoutGtfs > 0) {
            addInfo("Of %d patterns, %d did not reference any GTFS (apparently generated by scenario).".formatted(
                patterns.size(), nPatternsWithoutGtfs
            ));
        }
        if (nPatternsWithoutShapes > 0) {
            addInfo("Of %d patterns, %d had no shapes and used straight lines.".formatted(
                patterns.size(), nPatternsWithoutShapes
            ));
        }
        // After finding all links (TripPattern hops) in the SelectedLink area, release the GTFSFeeds which don't really
        // belong in a Modification. This avoids memory leaks, and protects us from inadvertently relying on or
        // modifying those feed objects later.
        feedForUnscopedId = null;

        // To confirm expected behavior, record all selected links in Modification.info for the user, and log to console.
        LOG.info("Selected links for CSV path output:");
        hopsInTripPattern.forEachEntry((int patternIndex, int[] stopPositions) -> {
            TripPattern tripPattern = network.transitLayer.tripPatterns.get(patternIndex);
            RouteInfo routeInfo = network.transitLayer.routes.get(tripPattern.routeIndex);
            String stopNames = Arrays.stream(stopPositions)
                    .map(s -> tripPattern.stops[s])
                    .mapToObj(network.transitLayer.stopNames::get)
                    .collect(Collectors.joining(", "));
            String message = String.format("Route %s direction %s after stop %s", routeInfo.getName(), tripPattern.directionId, stopNames);
            addInfo(message);
            LOG.info(message);
            return true;
        });

        // Store the resulting precomputed information in a SelectedLink instance on the TransportNetwork.
        // This could also be on the TransitLayer, but we may eventually want to include street edges in SelectedLink.
        network.selectedLink = new SelectedLink(network.transitLayer, hopsInTripPattern);
        return hasErrors();
    }

    // By returning false for both affects methods, we make a very shallow copy of the TransitNetwork for efficiency.

    @Override
    public boolean affectsStreetLayer() {
        return false;
    }

    @Override
    public boolean affectsTransitLayer() {
        return false;
    }

    @Override
    public int getSortOrder() {
        // This modification needs to be applied after any modifications affecting the transit network.
        // It appears this method is never called, maybe because sort order from CustomModificationHolder is used.
        return 80;
    }

    /**
     * Currently we do not include the GTFS shapes in the TransportNetwork, so in order to determine which routes pass
     * through a given geographic area, we need access to the original GTFS data. When resolving and applying
     * Modifications, the only thing available is the TransportNetwork itself. This method is used to supply any needed
     * GtfsFeeds keyed on their non-bundle-scoped, Conveyal-assigned feed UUID.
     * A TransportNetwork may be made from a bundle with multiple feeds, so we can't attach just one GTFSFeed.
     * The TransportNetwork does not directly retain information on which feeds were used to create it, but each
     * TripPattern retains a feedId as a prefix to its routeId in the format feedUUID:route_id.
     * However, those feed IDs lack the bundle scope (feed group ID) needed to get feeds from GtfsCache.
     * This all deviates significantly from pre-existing design, but does work and reveals some important
     * considerations for any future revisions of scenario application or network building system.
     */
    public void injectGtfs(Map<String, GTFSFeed> feedForUnscopedId) {
        this.feedForUnscopedId = feedForUnscopedId;
    }

    /**
     * The TransportNetwork does not directly retain information on which feeds were used to create it, but each of its
     * TripPatterns retains a feed-scoped routeId in this format: String.format("%s:%s", gtfs.feedId, route.route_id).
     * This feed ID is not bundle-scoped as expected by GtfsCache when loading feeds,so requires additional mapping.
     */
    private String feedIdForTripPattern (TripPattern tripPattern)  {
        String[] parts = tripPattern.routeId.split(":");
        if (parts.length == 0) {
            return null;
        } else {
            return parts[0];
        }
    }

}
