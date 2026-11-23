package com.conveyal.r5.analyst.scenario;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.flex.OnDemandIndex;
import com.conveyal.gtfs.geom.CPolygonal;
import com.conveyal.gtfs.geom.GeoJsonStreamer;
import com.conveyal.gtfs.geom.GeoJsonStreamer.IdSource;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.ExceptionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.file.FileCategory.DATASOURCES;

/// This Modification adds on-demand services, such as ride-hailing or dial-a-ride feeders,
/// connecting pairs of polygons. Each service picks riders up anywhere inside one polygon and
/// drops them off anywhere inside another, after a fixed wait to be picked up. Services are
/// directional. State a second service with the polygons reversed to allow travel in the return
/// direction.
///
/// The polygons are GeoJSON features. They can be read from a GeoJSON data source named by
/// dataSourceId, or written inline in the modification as the polygons property, or both. Each
/// feature must have a text property named id. When both sources are given, their polygons are
/// combined into one layer, and the ids must be unique across the two. Services and error or
/// warning messages refer to polygons by these ids.
///
/// Each service has a street mode (CAR or BICYCLE). A default can be stated for the whole
/// modification and overridden for individual services.
///
/// The services are represented and routed exactly like those loaded from GTFS-Flex feeds, but
/// they are available at all times on every date. They are used when the access modes or egress
/// modes include ON_DEMAND. Multiple modifications of this kind may be applied in one scenario.
public class AddOnDemand extends Modification {

    // Public parameters deserialized from JSON

    /// The ID of a GeoJSON data source containing polygons. This may be null when polygons are
    /// supplied inline. The data source must have been uploaded as GeoJSON and not in any other
    /// format.
    public String dataSourceId;

    /// Polygons included inline as a GeoJSON FeatureCollection. Contents are structured identically
    /// to a GeoJSON data source file. This may be null when a dataSourceId is supplied. Inline
    /// polygons are stored and transmitted with the modification itself, so this is intended for a
    /// relatively small number of simple polygons. Currently there is no serializer to write this
    /// field back out as JSON because it is unneeded (see [PolygonLayer.Serializer]).
    public PolygonLayer polygons;

    /// Default time in minutes a rider waits to be picked up after reaching the pick-up
    /// polygon, applied to every service that does not state its own. Defaults to zero,
    /// meaning immediate pick-up.
    public double waitMinutes = 0;

    /// Default multiplier on ride duration relative to use outside on-demand. This captures detours
    /// to serve other riders. Applied to every service that does not state its own. Defaults to 1.
    public double durationFactor = 1;

    /// Default street mode, applied to every service that does not state its own. This may be CAR
    /// or BICYCLE and defaults to CAR. Determines the streets a ride can use and its speed.
    /// CAR services drive at the speed limit of each street. BICYCLE services use the bike speed
    /// in the analysis request.
    public StreetMode streetMode = StreetMode.CAR;

    public List<Service> services;

    public static class Service {

        /// The ID of the polygon where riders are picked up.
        public String from;

        /// The ID of the polygon where riders are dropped off.
        public String to;

        /// The time in minutes a rider waits to be picked up after reaching the pick-up polygon.
        /// When absent, the modification's top-level default applies.
        public Double waitMinutes;

        /// Multiplier on ride duration relative to normal travel, representing detours to
        /// serve other riders. When absent, the modification's top-level default applies.
        public Double durationFactor;

        /// The street mode of the vehicle that carries riders, CAR or BICYCLE.
        /// When absent, the modification's top-level default applies.
        public StreetMode streetMode;

    }

    private transient List<OnDemand> resolved;

    @Override
    public boolean resolve (TransportNetwork network) {
        resolved = new ArrayList<>();
        if (dataSourceId == null && polygons == null) {
            addError("Polygons must be specified with a dataSourceId, inline polygons, or both.");
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
        if (!isVehicleMode(streetMode)) {
            addError("The modification-level streetMode must be CAR or BICYCLE.");
        }
        if (hasErrors()) return true;
        // Combine the polygons from both sources in a new layer each time this method is called.
        // The inline layer is left unchanged, so resolving this modification again (after a
        // failed attempt to apply a cached scenario) gives the same result.
        PolygonLayer layer = new PolygonLayer();
        if (polygons != null) {
            layer.add(polygons);
        }
        if (dataSourceId != null) {
            try {
                readDataSource(layer);
            } catch (Exception e) {
                addError("Could not load polygons: " + ExceptionUtils.shortAndLongString(e));
                return true;
            }
        }
        addErrors(layer.problems);
        Map<String, CPolygonal> polygonsById = layer.polygonsById;
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
            StreetMode mode = service.streetMode != null ? service.streetMode : this.streetMode;
            if (service.streetMode != null && !isVehicleMode(service.streetMode)) {
                addError(label + " must have a streetMode of CAR or BICYCLE.");
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
            od.streetMode = mode;
            od.fromWindowStart = 0;
            od.fromWindowEnd = Integer.MAX_VALUE;
            od.toWindowEnd = Integer.MAX_VALUE;
            od.alwaysActive = true;
            resolved.add(od);
        }
        return hasErrors();
    }

    /// Returns true when we allow on-demand services to use the given street mode.
    private static boolean isVehicleMode (StreetMode mode) {
        return mode == StreetMode.CAR || mode == StreetMode.BICYCLE;
    }

    /// Add the polygons in the GeoJSON data source to the given layer.
    private void readDataSource (PolygonLayer layer) throws Exception {
        FileStorageKey key = new FileStorageKey(DATASOURCES, dataSourceId, FileStorageFormat.GEOJSON.extension);
        File file = WorkerComponents.fileStorage.getFile(key);
        if (!file.isFile()) {
            addError(String.format("No GeoJSON data source was found with the ID '%s'. " +
                    "The data source must have been uploaded as GeoJSON.", dataSourceId));
            return;
        }
        try (InputStream inputStream = new FileInputStream(file)) {
            layer.read(new GeoJsonStreamer(inputStream, IdSource.ID_PROPERTY, null));
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
