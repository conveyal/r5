package com.conveyal.r5.analyst.scenario;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.file.FileStorageKey;
import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.flex.OnDemandIndex;
import com.conveyal.gtfs.geom.CPolygonal;
import com.conveyal.gtfs.geom.GeoJsonStreamer;
import com.conveyal.gtfs.geom.GeoJsonStreamer.IdSource;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.ExceptionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.file.FileCategory.DATASOURCES;

/// This Modification adds on-demand services, such as ride-hailing or dial-a-ride feeders,
/// connecting pairs of polygons in a GeoJSON data source. Each service picks riders up anywhere
/// inside one polygon and drops them off anywhere inside another, after a fixed wait to be picked
/// up. Services are directional. State a second service with the polygons reversed to allow
/// travel in the return direction.
///
/// The services are represented and routed exactly like those loaded from GTFS-Flex feeds.
/// They are available at all times on every date. Multiple modifications of this kind may be
/// applied in one scenario. Their services do not yet act on the post-transit egress side of trips.
public class AddOnDemand extends Modification {

    // Public parameters deserialized from JSON

    /// The identifier of the GeoJSON data source containing the polygons. Each feature must
    /// have a text property named id, unique within the layer. Services and error or warning
    /// messages refer to polygons by these ids.
    public String polygons;

    /// Default time in minutes a rider waits to be picked up after reaching the pick-up
    /// polygon, applied to every service that does not state its own. Defaults to zero,
    /// meaning immediate pick-up.
    public double waitMinutes = 0;

    /// Default multiplier on ride duration relative to driving directly, representing detours
    /// to serve other riders, applied to every service that does not state its own. Defaults to 1.
    public double durationFactor = 1;

    public List<Service> services;

    public static class Service {

        /// The ID of the polygon where riders are picked up.
        public String from;

        /// The ID of the polygon where riders are dropped off.
        public String to;

        /// The time in minutes a rider waits to be picked up after reaching the pick-up polygon.
        /// When absent, the modification's top-level default applies.
        public Double waitMinutes;

        /// Multiplier on ride duration relative to driving directly, representing detours to
        /// serve other riders. When absent, the modification's top-level default applies.
        public Double durationFactor;

    }

    private transient List<OnDemand> resolved;

    @Override
    public boolean resolve (TransportNetwork network) {
        resolved = new ArrayList<>();
        if (polygons == null) {
            addError("A polygon data source must be specified.");
        }
        if (services == null || services.isEmpty()) {
            addError("At least one service must be specified.");
        }
        if (waitMinutes < 0) {
            addError("The modification-level waitMinutes must be non-negative.");
        }
        if (!(durationFactor > 0)) {
            addError("The modification-level durationFactor must be positive.");
        }
        if (hasErrors()) return true;
        Map<String, CPolygonal> polygonsById = new HashMap<>();
        try {
            streamPolygons(polygonsById);
        } catch (Exception e) {
            addError("Could not load polygons: " + ExceptionUtils.shortAndLongString(e));
            return true;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < services.size(); i++) {
            Service service = services.get(i);
            String label = String.format("Service %d (%s to %s)", i, service.from, service.to);
            double wait = service.waitMinutes != null ? service.waitMinutes : this.waitMinutes;
            double factor = service.durationFactor != null ? service.durationFactor : this.durationFactor;
            if (service.waitMinutes != null && service.waitMinutes < 0) {
                addError(label + " must have a non-negative waitMinutes.");
            }
            if (service.durationFactor != null && !(service.durationFactor > 0)) {
                addError(label + " must have a positive durationFactor.");
            }
            OnDemand od = new OnDemand();
            od.id = service.from + ">" + service.to;
            if (service.from != null && service.to != null && !ids.add(od.id)) {
                addError(label + " duplicates an earlier service between the same polygons.");
                continue;
            }
            CPolygonal from = polygonForEnd(polygonsById, service.from, label, "from");
            CPolygonal to = polygonForEnd(polygonsById, service.to, label, "to");
            if (from == null || to == null) continue;
            od.fromPolygon = from;
            od.toPolygon = to;
            od.name = service.from + " to " + service.to;
            od.durationOffset = wait * 60;
            od.durationFactor = factor;
            od.fromWindowStart = 0;
            od.fromWindowEnd = Integer.MAX_VALUE;
            od.toWindowEnd = Integer.MAX_VALUE;
            od.alwaysActive = true;
            resolved.add(od);
        }
        return hasErrors();
    }

    private void streamPolygons (Map<String, CPolygonal> polygonsById) throws Exception {
        File file = WorkerComponents.fileStorage.getFile(new FileStorageKey(DATASOURCES, polygons));
        if (!file.isFile()) {
            addError("Polygon data source not found: " + polygons);
            return;
        }
        try (InputStream inputStream = new FileInputStream(file)) {
            GeoJsonStreamer streamer = new GeoJsonStreamer(inputStream, IdSource.ID_PROPERTY, null);
            streamer.stream((id, name, geometry) -> {
                if (polygonsById.putIfAbsent(id, geometry) != null) {
                    addError(String.format("More than one feature has the ID '%s'.", id));
                }
            });
            if (streamer.skippedFeatureCount() > 0) {
                addError(String.format("Ignored %d feature(s): %s.",
                        streamer.skippedFeatureCount(), streamer.describeProblems()));
            }
        }
    }

    /// Look up one end of a service, reporting any ID reference or geometry problems.
    private CPolygonal polygonForEnd (Map<String, CPolygonal> polygonsById, String id, String label, String end) {
        if (id == null) {
            addError(label + " does not specify a '" + end + "' polygon.");
            return null;
        }
        CPolygonal geometry = polygonsById.get(id);
        if (geometry == null) {
            addError(label + " refers to a polygon with unknown ID '" + id + "'.");
            return null;
        }
        if (!geometry.validate()) {
            addError(String.format("Polygon '%s' has unusable geometry: it is not a valid polygon.", id));
            return null;
        }
        return geometry;
    }

    /// A scenario copy of the transit layer shares the base network's on-demand index (if any).
    /// This method replaces it with a protective copy before adding services, so only the rare
    /// scenarios containing this modification pay for copying and re-indexing. When several of
    /// these modifications appear in one scenario, later ones harmlessly re-copy the copy.
    @Override
    public boolean apply (TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        if (transitLayer.onDemandIndex == null) {
            transitLayer.onDemandIndex = new OnDemandIndex();
        } else {
            transitLayer.onDemandIndex = transitLayer.onDemandIndex.scenarioCopy();
        }
        for (OnDemand od : resolved) {
            transitLayer.onDemandIndex.add(od);
        }
        return hasErrors();
    }

    @Override
    public int getSortOrder () {
        // Applied after modifications that create stops or streets, though nothing here refers to them yet.
        return 97;
    }

    @Override
    public boolean affectsStreetLayer () {
        return false;
    }

    @Override
    public boolean affectsTransitLayer () {
        return true;
    }

}
