package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.faresv2.FareLegRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import com.conveyal.r5.util.LambdaCounter;
import com.conveyal.r5.util.LocationIndexedLineInLocalCoordinateSystem;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LinearLocation;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;


/**
 * A key simplifying factor is that we don't handle overnight trips. This is fine for analysis at usual times of day.
 */
public class TransitLayer implements Serializable, Cloneable {

    /**
     * Maximum distance to record in distance tables, in meters.
     * Set to 3.5 km to match OTP GraphIndex.MAX_WALK_METERS but TODO should probably be reduced after Kansas City project.
     */
    public static final int WALK_DISTANCE_LIMIT_METERS = 2000;

    public static final boolean SAVE_SHAPES = false;

    /**
     * Distance limit for transfers, meters. Set to 1km which is slightly above OTP's 600m (which was specified as
     * 1 m/s with 600s max time, which is actually somewhat less than 600m due to extra costs due to steps etc.
     */
    public static final int TRANSFER_DISTANCE_LIMIT_METERS = 1000;

    /**
     * Distance limit from P+R to transit in meters
     */
    public static final int PARKRIDE_DISTANCE_LIMIT_METERS = 500;

    private static final Logger LOG = LoggerFactory.getLogger(TransitLayer.class);

    /**
     * The offset for fare areas. Since each stop is also a fare area, explicit fare areas are numbered starting with
     * this to keep them from ever colliding with stop IDs, as long as there are less than 1 billion stops in the network.
     *
     * TODO if this were smaller would it speed serialization due to variable-width integer encoding? 1 billion might be
     * excessive.
     */
    public static final int EXPLICIT_FARE_AREA_OFFSET = 1_000_000_000;

    /**
     * The offset for fare networks. Since each route is also a fare network, explicit fare networks are numbered starting with
     * this to keep them from ever colliding with stop IDs, as long as there are less than 1 billion stops in the network.
     */
    public static final int EXPLICIT_FARE_NETWORK_OFFSET = 1_000_000_000;

    /** Special int value used to indicate a field in GTFS-fares was left blank in the fare indexes */
    public static final int FARE_ID_BLANK = -1;

    /**
     * The time zone in which this TransportNetwork falls. It is read from a GTFS agency.
     * It defaults to GMT if no valid time zone can be found.
     */
    protected ZoneId timeZone;

    // Do we really need to store this? It does serve as a key into the GTFS MapDB.
    // It contains information that is temporarily also held in stopForIndex.
    public List<String> stopIdForIndex = new ArrayList<>();

    /** Fare zones for stops */
    public List<String> fareZoneForStop = new ArrayList<>();

    public List<String> parentStationIdForStop = new ArrayList<>();

    // Inverse map of stopIdForIndex, reconstructed from that list (not serialized). No-entry value is -1.
    public transient TObjectIntMap<String> indexForStopId;

    // This is used as an initial size estimate for many lists.
    public static final int TYPICAL_NUMBER_OF_STOPS_PER_TRIP = 30;

    public List<TripPattern> tripPatterns = new ArrayList<>();

    // Maybe we need a StopStore that has (streetVertexForStop, transfers, flags, etc.)
    public TIntList streetVertexForStop = new TIntArrayList();

    // Inverse map of streetVertexForStop, and reconstructed from that list.
    public transient TIntIntMap stopForStreetVertex;

    // For each stop, a packed list of transfers to other stops
    // FIXME we may currently be storing weight or time to reach other stop, which we did to avoid floating point division. Instead, store distances in millimeters, and divide by speed in mm/sec.
    public List<TIntList> transfersForStop = new ArrayList<>();

    /** Information about a route */
    public List<RouteInfo> routes = new ArrayList<>();

    /** The names of the stops */
    public List<String> stopNames = new ArrayList<>();

    public List<TIntList> patternsForStop;

    public List<Service> services = new ArrayList<>();

    /** Map from frequency entry ID to pattern index, trip index, frequency entry index */
    public Map<String, int[]> frequencyEntryIndexForId;

    /** If true at index stop allows boarding with wheelchairs **/
    public BitSet stopsWheelchair;

    // TODO there is probably a better way to do this, but for now we need to retain stop object for linking to streets
    public transient List<Stop> stopForIndex = new ArrayList<>();

    // The coordinates of a place roughly in the center of the transit network, for centering maps and coordinate systems.
    public double centerLon;
    public double centerLat;

    /** does this TransitLayer have any frequency-based trips? */
    public boolean hasFrequencies = false;

    /** Does this TransitLayer have any schedules */
    public boolean hasSchedules = false;

    /**
     * For each transit stop, an int-int map giving the walking distance to every reachable street vertex from that stop.
     * This is the result of running a distance-constrained street search outward from every stop in the graph.
     * If these tables are present, we serialize them when persisting a network to disk to avoid recalculating them
     * upon re-load. However, these tables are only computed when the network is first built in certain code
     * paths used for analysis work. The tables are not necessary for basic point-to-point routing.
     * Serializing this table makes network files much bigger and makes our checks to ensure that scenario application
     * does not damage base graphs slower.
     */
    public List<TIntIntMap> stopToVertexDistanceTables;

    /**
     * The TransportNetwork containing this TransitLayer. This link up the object tree also allows us to access the
     * StreetLayer associated with this TransitLayer in the same TransportNetwork without maintaining bidirectional
     * references between the two layers.
     */
    public TransportNetwork parentNetwork = null;

    public Map<String, Fare> fares;

    /**
     * The fare areas for each stop, in GTFS Fares v2.
     *
     * This is a list of fare area IDs. Each stop is implicitly a fare area, so the list of fare areas for a stop always
     * contains the stop ID. Explicit fare area IDs are made unique from stop int IDs by adding a large constant (10 million).
     */
    public TIntObjectMap<TIntList> fareAreasForStop = new TIntObjectHashMap<>();

    // TODO Fare Areas for trip, stop_sequence pair

    /** Fare networks for each route */
    public TIntObjectMap<RoaringBitmap> fareNetworksForRoute = new TIntObjectHashMap<>();

    /**
     * Is fare network EXPLICIT_FARE_NETWORK_OFFSET + i an as_route fare network (i.e. several legs within the network
     * should be matched as a single journey?)
     *
     * Note that fare networks are offset to keep them distinct from integer route IDs since routes are also implicit
     * fare networks. It is not recommended to query this directly but instead use getFareNetworkAsRoute(fareNetworkId)
     * and setFareNetworkAsRoute(fareNetworkId) which handles the integer conversions.
     */
    public RoaringBitmap fareNetworkAsRoute = new RoaringBitmap();

    /**
     * The fare leg rules for this transport network.
     * If memory becomes a problem, FareLegRule could be replaced with a proxy class that only contains amount
     */
    public List<FareLegRuleInfo> fareLegRules = new ArrayList<>();

    // indices for fare leg rules. Note that each index also contains an entry for key FARE_ID_BLANK which indicates that
    //the field was left blank

    /** Fare leg rule for fare network ID (either explicit or implicit) */
    public TIntObjectMap<RoaringBitmap> fareLegRulesForFareNetworkId = new TIntObjectHashMap<>();

    /** Fare leg rule for from area id */
    public TIntObjectMap<RoaringBitmap> fareLegRulesForFromAreaId = new TIntObjectHashMap<>();

    /** Fare leg rule for to area id */
    public TIntObjectMap<RoaringBitmap> fareLegRulesForToAreaId = new TIntObjectHashMap<>();

    // TODO contains_area_id, leg_group_id, timeframes

    /**
     * The fare transfer rules for this transport network.
     */
    public List<FareTransferRuleInfo> fareTransferRules = new ArrayList<>();

    /** Fare transfer rule index for from_leg_group_id, with key for FARE_ID_BLANK containing fare transfer rules with empty from_leg_group_id */
    public TIntObjectMap<RoaringBitmap> fareTransferRulesForFromLegGroupId = new TIntObjectHashMap<>();

    /** Fare transfer rule index for to_leg_group_id, with key for FARE_ID_BLANK containing fare transfer rules with empty to_leg_group_id */
    public TIntObjectMap<RoaringBitmap> fareTransferRulesForToLegGroupId = new TIntObjectHashMap<>();

    /** Map from feed ID to feed CRC32 to ensure that we can't apply scenarios to the wrong feeds */
    public Map<String, Long> feedChecksums = new HashMap<>();

    /**
     * A string uniquely identifying the contents of this TransitLayer among all TransitLayers.
     * When no scenario has been applied, this field will contain the ID of the containing TransportNetwork.
     * When a scenario has modified this StreetLayer, this field will be changed to the scenario's ID.
     * We need a way to know what transit information is in the layer independent of object identity, which is lost in
     * a round trip through serialization. This also allows re-using cached information for multiple scenarios that
     * don't modify the transit network. (This never happens yet, but will when we allow street editing.)
     */
    public String scenarioId;

    /** Load a GTFS feed with full load level */
    public void loadFromGtfs (GTFSFeed gtfs) throws DuplicateFeedException {
        loadFromGtfs(gtfs, LoadLevel.FULL);
    }

    /**
     * Load data from a GTFS feed. Call multiple times to load multiple feeds.
     */
    public void loadFromGtfs (GTFSFeed gtfs, LoadLevel level) throws DuplicateFeedException {
        if (feedChecksums.containsKey(gtfs.feedId)) {
            throw new DuplicateFeedException(gtfs.feedId);
        }

        // checksum feed and add to checksum cache
        feedChecksums.put(gtfs.feedId, gtfs.checksum);

        // Load stops.
        // ID is the GTFS string ID, stopIndex is the zero-based index, stopVertexIndex is the index in the street layer.
        TObjectIntMap<String> indexForUnscopedStopId = new TObjectIntHashMap<>();
        stopsWheelchair = new BitSet(gtfs.stops.size());
        for (Stop stop : gtfs.stops.values()) {
            int stopIndex = stopIdForIndex.size();
            String scopedStopId = String.join(":", stop.feed_id, stop.stop_id);
            // This is only used while building the TransitNetwork to look up StopTimes from the same feed.
            indexForUnscopedStopId.put(stop.stop_id, stopIndex);
            stopIdForIndex.add(scopedStopId);
            // intern zone IDs to save memory
            fareZoneForStop.add(stop.zone_id);
            parentStationIdForStop.add(stop.parent_station);
            stopForIndex.add(stop);
            if (stop.wheelchair_boarding != null && stop.wheelchair_boarding.trim().equals("1")) {
                stopsWheelchair.set(stopIndex);
            }
            if (level == LoadLevel.FULL) {
                stopNames.add(stop.stop_name);
            }
        }

        // Load service periods, assigning integer codes which will be referenced by trips and patterns.
        TObjectIntMap<String> serviceCodeNumber = new TObjectIntHashMap<>(20, 0.5f, -1);
        gtfs.services.forEach((serviceId, service) -> {
            int serviceIndex = services.size();
            services.add(service);
            serviceCodeNumber.put(serviceId, serviceIndex);
            LOG.debug("Service {} has ID {}", serviceIndex, serviceId);
        });

        // Group trips by stop pattern (including pickup/dropoff type) and fill stop times into patterns.
        // Also group trips by the blockId they belong to, and chain them together if they allow riders to stay on board
        // the vehicle from one trip to the next, even if it changes routes or directions. This is called "interlining".
        gtfs.findPatterns();

        LOG.info("Creating trip patterns and schedules.");

        // These are temporary maps used only for grouping purposes.
        Map<String, TripPattern> tripPatternForPatternId = new HashMap<>();
        Multimap<String, TripSchedule> tripsForBlock = HashMultimap.create();

        // Keyed with unscoped route_id, which is fine as this is for a single GTFS feed
        TObjectIntMap<String> routeIndexForRoute = new TObjectIntHashMap<>();
        int nTripsAdded = 0;
        int nZeroDurationHops = 0;
        TRIPS: for (String tripId : gtfs.trips.keySet()) {
            Trip trip = gtfs.trips.get(tripId);
            Route route = gtfs.routes.get(trip.route_id);
            // Construct the stop pattern and schedule for this trip.
            String scopedRouteId = String.join(":", gtfs.feedId, trip.route_id);
            TIntList arrivals = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList departures = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList stopSequences = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);

            int previousDeparture = Integer.MIN_VALUE;

            int nStops = 0;

            Iterable<StopTime> stopTimes;

            try {
                stopTimes = gtfs.getInterpolatedStopTimesForTrip(tripId);
            } catch (GTFSFeed.FirstAndLastStopsDoNotHaveTimes e) {
                LOG.warn("First and last stops do not both have times specified on trip {} on route {}, skipping this as interpolation is impossible", trip.trip_id, trip.route_id);
                continue TRIPS;
            }

            for (StopTime st : stopTimes) {
                arrivals.add(st.arrival_time);
                departures.add(st.departure_time);
                stopSequences.add(st.stop_sequence);

                if (previousDeparture > st.arrival_time || st.arrival_time > st.departure_time) {
                    LOG.warn("Negative-time travel at stop {} on trip {} on route {}, skipping this trip as it will wreak havoc with routing", st.stop_id, trip.trip_id, trip.route_id);
                    continue TRIPS;
                }

                if (previousDeparture == st.arrival_time) { //Teleportation: arrive at downstream stop immediately after departing upstream
                    //often the result of a stop_times input with time values rounded to the nearest minute.
                    //TODO check if the distance of the hop is reasonably traveled in less than 60 seconds, which may vary by mode.
                    nZeroDurationHops++;
                }

                previousDeparture = st.departure_time;

                nStops++;
            }

            if (nStops == 0) {
                LOG.warn("Trip {} on route {} {} has no stops, it will not be used", trip.trip_id, trip.route_id, route.route_short_name);
                continue;
            }

            String patternId = gtfs.patternForTrip.get(tripId);

            TripPattern tripPattern = tripPatternForPatternId.get(patternId);
            if (tripPattern == null) {
                tripPattern = new TripPattern(String.format("%s:%s", gtfs.feedId, route.route_id), stopTimes, indexForUnscopedStopId);

                // if we haven't seen the route yet _from this feed_ (as IDs are only feed-unique)
                // create it.
                if (level == LoadLevel.FULL) {
                    if (!routeIndexForRoute.containsKey(trip.route_id)) {
                        int routeIndex = routes.size();
                        RouteInfo ri = new RouteInfo(route, gtfs.agency.get(route.agency_id));
                        routes.add(ri);
                        routeIndexForRoute.put(trip.route_id, routeIndex);
                    }

                    tripPattern.routeIndex = routeIndexForRoute.get(trip.route_id);

                    if (trip.shape_id != null && SAVE_SHAPES) {
                        Shape shape = gtfs.getShape(trip.shape_id);
                        if (shape == null) LOG.warn("Shape {} for trip {} was missing", trip.shape_id, trip.trip_id);
                        else {
                            // TODO this will not work if some trips in the pattern don't have shapes
                            tripPattern.shape = shape.geometry;

                            // project stops onto shape
                            boolean stopsHaveShapeDistTraveled = StreamSupport.stream(stopTimes.spliterator(), false)
                                    .noneMatch(st -> Double.isNaN(st.shape_dist_traveled));
                            boolean shapePointsHaveDistTraveled = DoubleStream.of(shape.shape_dist_traveled)
                                    .noneMatch(Double::isNaN);

                            LinearLocation[] locations;

                            if (stopsHaveShapeDistTraveled && shapePointsHaveDistTraveled) {
                                // create linear locations from dist traveled
                                locations = StreamSupport.stream(stopTimes.spliterator(), false)
                                        .map(st -> {
                                            double dist = st.shape_dist_traveled;

                                            int segment = 0;

                                            while (segment < shape.shape_dist_traveled.length - 2 &&
                                                    dist > shape.shape_dist_traveled[segment + 1]
                                                    ) segment++;

                                            double endSegment = shape.shape_dist_traveled[segment + 1];
                                            double beginSegment = shape.shape_dist_traveled[segment];
                                            double proportion = (dist - beginSegment) / (endSegment - beginSegment);

                                            return new LinearLocation(segment, proportion);
                                        }).toArray(LinearLocation[]::new);
                            } else {
                                // naive snapping
                                LocationIndexedLineInLocalCoordinateSystem line =
                                        new LocationIndexedLineInLocalCoordinateSystem(shape.geometry.getCoordinates());

                                locations = StreamSupport.stream(stopTimes.spliterator(), false)
                                        .map(st -> {
                                            Stop stop = gtfs.stops.get(st.stop_id);
                                            return line.project(new Coordinate(stop.stop_lon, stop.stop_lat));
                                        })
                                        .toArray(LinearLocation[]::new);
                            }

                            tripPattern.stopShapeSegment = new int[locations.length];
                            tripPattern.stopShapeFraction = new float[locations.length];

                            for (int i = 0; i < locations.length; i++) {
                                tripPattern.stopShapeSegment[i] = locations[i].getSegmentIndex();
                                tripPattern.stopShapeFraction[i] = (float) locations[i].getSegmentFraction();
                            }
                        }
                    }
                }

                tripPatternForPatternId.put(patternId, tripPattern);
                tripPattern.originalId = tripPatterns.size();
                tripPatterns.add(tripPattern);
            }
            tripPattern.setOrVerifyDirection(trip.direction_id);
            int serviceCode = serviceCodeNumber.get(trip.service_id);

            // TODO there's no reason why we can't just filter trips like this, correct?
            // TODO this means that invalid trips still have empty patterns created
            Collection<Frequency> frequencies = gtfs.getFrequencies(trip.trip_id);
            TripSchedule tripSchedule = TripSchedule.create(trip, arrivals.toArray(), departures.toArray(), frequencies, stopSequences.toArray(), serviceCode);
            if (tripSchedule == null) continue;

            tripPattern.addTrip(tripSchedule);

            this.hasFrequencies = this.hasFrequencies || tripSchedule.headwaySeconds != null;
            this.hasSchedules = this.hasSchedules || tripSchedule.headwaySeconds == null;

            nTripsAdded += 1;
            // Record which block this trip belongs to, if any.
            if ( ! Strings.isNullOrEmpty(trip.block_id)) {
                tripsForBlock.put(trip.block_id, tripSchedule);
            }
        }
        LOG.info("Done creating {} trips on {} patterns.", nTripsAdded, tripPatternForPatternId.size());

        LOG.info("{} zero-duration hops found.", nZeroDurationHops);

        LOG.info("Chaining trips together according to blocks to model interlining...");
        // Chain together trips served by the same vehicle that allow transfers by simply staying on board.
        // Elsewhere this is done by grouping by (serviceId, blockId) but this is not supported by the spec.
        // Discussion started on gtfs-changes.
        tripsForBlock.asMap().forEach((blockId, trips) -> {
            TripSchedule[] schedules = trips.toArray(new TripSchedule[trips.size()]);
            Arrays.sort(schedules); // Sorts on first departure time
            for (int i = 0; i < schedules.length - 1; i++) {
                schedules[i].chainTo(schedules[i + 1]);
            }
        });
        LOG.info("Done chaining trips together according to blocks.");

        LOG.info("Sorting trips on each pattern");
        for (TripPattern tripPattern : tripPatternForPatternId.values()) {
            Collections.sort(tripPattern.tripSchedules);
        }
        LOG.info("done sorting");

        LOG.info("Finding the approximate center of the transport network...");
        findCenter(gtfs.stops.values());

        //Set transportNetwork timezone
        //If there are no agencies (which is strange) it is GMT
        //Otherwise it is set to first valid agency timezone and warning is shown if agencies have different timezones
        if (gtfs.agency.size() == 0) {
            timeZone = ZoneId.of("GMT");
            LOG.warn("graph contains no agencies; API request times will be interpreted as GMT.");
        } else {
            for (Agency agency : gtfs.agency.values()) {
                if (agency.agency_timezone == null) {
                    LOG.warn("Agency {} is without timezone", agency.agency_name);
                    continue;
                }
                ZoneId tz;
                try {
                    tz = ZoneId.of(agency.agency_timezone);
                } catch (ZoneRulesException z) {
                    LOG.error("Agency {} in GTFS with timezone '{}' wasn't found in timezone database reason: {}", agency.agency_name, agency.agency_timezone, z.getMessage());
                    //timezone will be set to GMT if it is still empty after for loop
                    continue;
                } catch (DateTimeException dt) {
                    LOG.error("Agency {} in GTFS has timezone in wrong format:'{}'. Expected format: area/city ", agency.agency_name, agency.agency_timezone);
                    //timezone will be set to GMT if it is still empty after for loop
                    continue;
                }
                //First time setting timezone
                if (timeZone == null) {
                    LOG.info("TransportNetwork time zone set to {} from agency '{}' and agency_timezone:{}", tz,
                        agency.agency_name, agency.agency_timezone);
                    timeZone = tz;
                } else if (!timeZone.equals(tz)) {
                    LOG.error("agency time zone {} differs from TransportNetwork time zone: {}. This will be problematic.", tz,
                        timeZone);
                }
            }

            //This can only happen if all agencies have empty timezones
            if (timeZone == null) {
                timeZone = ZoneId.of("GMT");
                LOG.warn(
                    "No agency in graph had valid timezone; API request times will be interpreted as GMT.");
            }
        }

        if (level == LoadLevel.FULL) {
            this.fares = new HashMap<>(gtfs.fares);
        }

        // Will be useful in naming patterns.
//        LOG.info("Finding topology of each route/direction...");
//        Multimap<T2<String, Integer>, TripPattern> patternsForRouteDirection = HashMultimap.create();
//        tripPatterns.forEach(tp -> patternsForRouteDirection.put(new T2(tp.routeId, tp.directionId), tp));
//        for (T2<String, Integer> routeAndDirection : patternsForRouteDirection.keySet()) {
//            RouteTopology topology = new RouteTopology(routeAndDirection.first, routeAndDirection.second, patternsForRouteDirection.get(routeAndDirection));
//        }

        try {
            // we only support a subset of Fares V2, and many exceptions may be thrown if the feed uses more than that
            // subset. Still allow graph to build without fare information if that is the case.
            loadFaresV2(gtfs, indexForUnscopedStopId, routeIndexForRoute);
        } catch (Exception e) {
            LOG.warn("Exception loading GTFS Fares V2, fare routing will not be available", e);
            clearFaresV2();
        }

        if (feedChecksums.size() > 1) {
            LOG.warn("Multiple feeds specified, GTFS-Fares V2 will be unavailable");
            clearFaresV2();
        }
    }

    /** Clear GTFS-Fares V2 information after a load error */
    private void clearFaresV2 () {
        fareLegRules.clear();
        fareTransferRules.clear();
        fareLegRulesForFromAreaId.clear();
        fareLegRulesForFareNetworkId.clear();
        fareLegRulesForToAreaId.clear();
        fareTransferRulesForFromLegGroupId.clear();
        fareTransferRulesForToLegGroupId.clear();
        fareAreasForStop.clear();
        fareNetworkAsRoute.clear();
        fareNetworksForRoute.clear();
    }

    /** Load GTFS-Fares V2 information from a feed */
    private void loadFaresV2 (GTFSFeed feed, TObjectIntMap<String> indexForUnscopedStopId,
                              TObjectIntMap<String> indexForUnscopedRouteId) {
        LOG.info("Loading GTFS-Fares V2");

        // due to symmetrical fare legs, one fare leg rule ID can match multiple fare leg rules
        Map<String, TIntList> fareLegRuleForLegGroupId = new HashMap<>();
        TObjectIntMap<String> fareNetworkForId = new TObjectIntHashMap<>();
        TObjectIntMap<String> fareAreaForId = new TObjectIntHashMap<>();

        LOG.info("Loading fare areas");
        for (int i = 0; i < stopIdForIndex.size(); i++) {
            fareAreasForStop.put(i, new TIntArrayList());
            fareAreasForStop.get(i).add(i); // every stop is a fare area
            fareAreaForId.put(stopForIndex.get(i).stop_id, i); // need to get unscoped id
        }

        // TODO this will not work if there are multiple feeds
        int fareAreaIdx = EXPLICIT_FARE_AREA_OFFSET;
        for (FareArea fareArea : feed.fare_areas.values()) {
            for (FareArea.FareAreaMember member : fareArea.members) {
                if (member.trip_id != null)
                    throw new IllegalArgumentException("Trip-based fare area membership not supported!");

                int stopIndex = indexForUnscopedStopId.get(member.stop_id);
                fareAreasForStop.get(stopIndex).add(fareAreaIdx);
            }
            fareAreaForId.put(fareArea.fare_area_id, fareAreaIdx);
            fareAreaIdx++;
        }

        LOG.info("Loaded {} fare areas", fareAreaForId.size());

        LOG.info("Loading fare networks");
        // TODO will not work if there are multiple feeds
        for (int i = 0; i < routes.size(); i++) {
            fareNetworkForId.put(routes.get(i).route_id, i);
            fareNetworksForRoute.put(i, new RoaringBitmap());
            fareNetworksForRoute.get(i).add(i); // every route is an implicit fare network
        }

        // TODO this will not work if there are multiple feeds
        int fareNetworkIdx = EXPLICIT_FARE_NETWORK_OFFSET;
        for (FareNetwork fareNetwork : feed.fare_networks.values()) {
            fareNetworkForId.put(fareNetwork.fare_network_id, fareNetworkIdx);
            if (fareNetwork.as_route == 1) fareNetworkAsRoute.add(fareNetworkIdx);

            for (String routeId : fareNetwork.route_ids) {
                int routeIdx = indexForUnscopedRouteId.get(routeId);
                fareNetworksForRoute.get(routeIdx).add(fareNetworkIdx);
            }

            fareNetworkIdx++;
        }

        LOG.info("Loaded {} fare networks", fareNetworkForId.size());

        LOG.info("Loading fare leg rules");
        for (FareLegRule rule : feed.fare_leg_rules) {
            fareLegRules.add(new FareLegRuleInfo(rule));
            int fareLegRuleIdx = fareLegRules.size() - 1;
            if (rule.leg_group_id != null) {
                if (fareLegRuleForLegGroupId.containsKey(rule.leg_group_id)) {
                    throw new IllegalArgumentException("Fare leg group ID " + rule.leg_group_id + " is duplicated");
                }
                fareLegRuleForLegGroupId.put(rule.leg_group_id, new TIntArrayList());
                fareLegRuleForLegGroupId.get(rule.leg_group_id).add(fareLegRuleIdx);
            }

            // build indices
            int fareNetworkId;
            if (rule.fare_network_id != null) {
                if (!fareNetworkForId.containsKey(rule.fare_network_id)) {
                    throw new IllegalArgumentException("Fare network ID referenced in fare_leg_rules not present: " +
                            rule.fare_network_id);
                }

                fareNetworkId = fareNetworkForId.get(rule.fare_network_id);
            } else fareNetworkId = FARE_ID_BLANK;

            if (!fareLegRulesForFareNetworkId.containsKey(fareNetworkId))
                fareLegRulesForFareNetworkId.put(fareNetworkId, new RoaringBitmap());
            fareLegRulesForFareNetworkId.get(fareNetworkId).add(fareLegRuleIdx);

            int fromAreaIdx;
            if (rule.from_area_id != null) {
                if (!fareAreaForId.containsKey(rule.from_area_id)) {
                    throw new IllegalArgumentException("Fare area ID referenced in fare_leg_rules not present: " +
                            rule.from_area_id);
                }
                fromAreaIdx = fareAreaForId.get(rule.from_area_id);
            } else fromAreaIdx = FARE_ID_BLANK;

            if (!fareLegRulesForFromAreaId.containsKey(fromAreaIdx)) {
                fareLegRulesForFromAreaId.put(fromAreaIdx, new RoaringBitmap());
            }
            fareLegRulesForFromAreaId.get(fromAreaIdx).add(fareLegRuleIdx);

            int toAreaIdx;
            if (rule.to_area_id != null) {
                if (!fareAreaForId.containsKey(rule.to_area_id)) {
                    throw new IllegalArgumentException("Fare area ID referenced in fare_leg_rules not present: " +
                            rule.to_area_id);
                }
                toAreaIdx = fareAreaForId.get(rule.to_area_id);
            } else toAreaIdx = FARE_ID_BLANK;

            if (!fareLegRulesForToAreaId.containsKey(toAreaIdx)) {
                fareLegRulesForToAreaId.put(toAreaIdx, new RoaringBitmap());
            }
            fareLegRulesForToAreaId.get(toAreaIdx).add(fareLegRuleIdx);

            if (rule.service_id != null) throw new IllegalArgumentException("Service IDs not supported in fare_leg_rules");
            if (rule.contains_area_id != null) throw new IllegalArgumentException("contains_area_id not supported in fare_leg_rules");
            if (rule.to_timeframe_id != null || rule.from_timeframe_id != null)
                throw new IllegalArgumentException("timeframes not supported in fare_leg_rules");
            if (!Double.isNaN(rule.min_fare_distance) || !Double.isNaN(rule.max_fare_distance))
                throw new IllegalArgumentException("Fare distances not supported in fare_leg_rules");

            if (rule.is_symmetrical == 1) {
                // Can't just add the same rule backwards, because of how the matching works. Consider a rule for travel
                // from Zone A to Zone B that is symmetrical. If we add the same rule index to both directions, the rule
                // will also match trips from A to A or B to B.
                fareLegRules.add(new FareLegRuleInfo(rule));
                fareLegRuleIdx = fareLegRules.size() -1;
                if (rule.leg_group_id != null)
                    // no contains check needed, forward rule already added above
                    fareLegRuleForLegGroupId.get(rule.leg_group_id).add(fareLegRuleIdx);

                // no contains check needed for same reason
                fareLegRulesForFareNetworkId.get(fareNetworkId).add(fareLegRuleIdx);

                if (!fareLegRulesForFromAreaId.containsKey(toAreaIdx)) {
                    fareLegRulesForFromAreaId.put(toAreaIdx, new RoaringBitmap());
                }
                fareLegRulesForFromAreaId.get(toAreaIdx).add(fareLegRuleIdx);

                if (!fareLegRulesForToAreaId.containsKey(fromAreaIdx)) {
                    fareLegRulesForToAreaId.put(fromAreaIdx, new RoaringBitmap());
                }
                fareLegRulesForToAreaId.get(fromAreaIdx).add(fareLegRuleIdx);
            }
        }

        LOG.info("Loaded {} fare leg rules", fareLegRules.size());

        LOG.info("Loading fare transfer rules");
        TIntList blankFareList = new TIntArrayList();
        blankFareList.add(FARE_ID_BLANK);
        for (FareTransferRule rule : feed.fare_transfer_rules) {
            fareTransferRules.add(new FareTransferRuleInfo(rule));
            int fareTransferRuleIdx = fareTransferRules.size() - 1;

            TIntList fromLegIdxs;
            if (rule.from_leg_group_id != null) {
                if (!fareLegRuleForLegGroupId.containsKey(rule.from_leg_group_id)) {
                    throw new IllegalArgumentException("Fare leg group ID referenced in fare_transfer_rules not present: " +
                            rule.from_leg_group_id);
                }
                fromLegIdxs = fareLegRuleForLegGroupId.get(rule.from_leg_group_id);
            } else fromLegIdxs = blankFareList;


            for (TIntIterator it = fromLegIdxs.iterator(); it.hasNext(); ) {
                int fromLegIdx = it.next();
                if (!fareTransferRulesForFromLegGroupId.containsKey(fromLegIdx)) {
                    fareTransferRulesForFromLegGroupId.put(fromLegIdx, new RoaringBitmap());
                }
                fareTransferRulesForFromLegGroupId.get(fromLegIdx).add(fareTransferRuleIdx);
            }

            TIntList toLegIdxs;
            if (rule.to_leg_group_id != null) {
                if (!fareLegRuleForLegGroupId.containsKey(rule.to_leg_group_id)) {
                    throw new IllegalArgumentException("Fare leg group ID referenced in fare_transfer_rules not present: " +
                            rule.to_leg_group_id);
                }
                toLegIdxs = fareLegRuleForLegGroupId.get(rule.to_leg_group_id);
            } else toLegIdxs = blankFareList;

            for (TIntIterator it = toLegIdxs.iterator(); it.hasNext(); ) {
                int toLegIdx = it.next();
                if (!fareTransferRulesForToLegGroupId.containsKey(toLegIdx)) {
                    fareTransferRulesForToLegGroupId.put(toLegIdx, new RoaringBitmap());
                }
                fareTransferRulesForToLegGroupId.get(toLegIdx).add(fareTransferRuleIdx);
            }

            if (rule.is_symmetrical == 1) {
                throw new UnsupportedOperationException("is_symmetrical not yet supported for fare_transfer_rules");
            }
        }

        LOG.info("Loaded {} fare transfer rules", fareTransferRules.size());
    }

    // The median of all stopTimes would be best but that involves sorting a huge list of numbers.
    // So we just use the mean of all stops for now.
    private void findCenter (Collection<Stop> stops) {
        double lonSum = 0;
        double latSum = 0;
        for (Stop stop : stops) {
            latSum += stop.stop_lat;
            lonSum += stop.stop_lon;
        }
        // Stops is a HashMap so size() is fast. If it ever becomes a MapDB BTree, we may want to do this differently.
        centerLat = latSum / stops.size();
        centerLon = lonSum / stops.size();
    }

    /** (Re-)build transient indexes of this TransitLayer, connecting stops to patterns etc. */
    public void rebuildTransientIndexes () {
        LOG.info("Rebuilding transient indices.");

        // 1. Which patterns pass through each stop?
        // We could store references to patterns rather than indexes.
        int nStops = stopIdForIndex.size();
        patternsForStop = new ArrayList<>(nStops);
        for (int i = 0; i < nStops; i++) {
            patternsForStop.add(new TIntArrayList());
        }
        int p = 0;
        for (TripPattern pattern : tripPatterns) {
            for (int stopIndex : pattern.stops) {
                if (!patternsForStop.get(stopIndex).contains(p)) {
                    patternsForStop.get(stopIndex).add(p);
                }
            }
            p++;
        }

        // 2. What street vertex represents each transit stop? Invert the serialized map.
        stopForStreetVertex = new TIntIntHashMap(streetVertexForStop.size(), 0.5f, -1, -1);
        for (int s = 0; s < streetVertexForStop.size(); s++) {
            stopForStreetVertex.put(streetVertexForStop.get(s), s);
        }

        // 3. What is the integer index for each GTFS stop ID?
        indexForStopId = new TObjectIntHashMap<>(stopIdForIndex.size(), 0.5f, -1);
        for (int s = 0; s < stopIdForIndex.size(); s++) {
            indexForStopId.put(stopIdForIndex.get(s), s);
        }

        // 4. What are the indices for each frequency entry?
        frequencyEntryIndexForId = new HashMap<>();

        for (int patternIdx = 0; patternIdx < tripPatterns.size(); patternIdx++) {
            TripPattern pattern = tripPatterns.get(patternIdx);
            for (int tripScheduleIdx = 0; tripScheduleIdx < pattern.tripSchedules.size(); tripScheduleIdx++) {
                TripSchedule schedule = pattern.tripSchedules.get(tripScheduleIdx);
                if (schedule.headwaySeconds == null) continue;

                for (int frequencyEntryIdx = 0; frequencyEntryIdx < schedule.headwaySeconds.length; frequencyEntryIdx++) {
                    frequencyEntryIndexForId.put(schedule.frequencyEntryIds[frequencyEntryIdx],
                            new int [] { patternIdx, tripScheduleIdx, frequencyEntryIdx });
                }
            }
        }

        LOG.info("Done rebuilding transient indices.");
    }

    /**
     * Run a distance-constrained street search from every transit stop in the graph.
     * Store the distance to every reachable street vertex for each of these origin stops.
     * If a scenario has been applied, we need to build tables for any newly created stops and any stops within
     * transfer distance or access/egress distance of those new stops. In that case a rebuildZone geometry should be
     * supplied. If rebuildZone is null, a complete rebuild of all tables will occur for all stops.
     * @param rebuildZone the zone within which to rebuild tables in FIXED-POINT DEGREES, or null to build all tables.
     */
    public void buildDistanceTables(Geometry rebuildZone) {

        LOG.info("Finding distances from transit stops to street vertices.");
        if (rebuildZone != null) {
            LOG.info("Selectively finding distances for only those stops potentially affected by scenario application.");
        }

        LambdaCounter buildCounter = new LambdaCounter(LOG, getStopCount(), 1000,
                "Computed distances to street vertices from {} of {} transit stops.");

        // Working in parallel, create a new list containing one distance table for each stop index, optionally
        // skipping stops falling outside the specified geometry.
        stopToVertexDistanceTables = IntStream.range(0, getStopCount()).parallel().mapToObj(stopIndex -> {
            if (rebuildZone != null) {
                // Skip existing or new stops outside the zone that may be affected by the scenario.
                Point p = getJTSPointForStopFixed(stopIndex);
                if (p == null || !rebuildZone.contains(p)) {
                    // This stop can't be affected, return any existing one.
                    return stopIndex < stopToVertexDistanceTables.size() ? stopToVertexDistanceTables.get(stopIndex) : null;
                }
            }
            buildCounter.increment();
            return this.buildOneDistanceTable(stopIndex);
        }).collect(Collectors.toList());
        buildCounter.done();
    }

    /**
     * Perform a single on-street WALK search from the specified transit stop.
     * Return the distance in millimeters to every reached street vertex.
     * @param stop the internal integer stop ID for which to build a distance table.
     * @return a map from street vertex numbers to distances in millimeters
     */
    public TIntIntMap buildOneDistanceTable(int stop) {
        int originVertex = streetVertexForStop.get(stop);
        if (originVertex == -1) {
            // -1 indicates that this stop is not linked to the street network.
            LOG.warn("Stop {} has not been linked to the street network, cannot build a distance table for it.", stop);
            return null;
        }
        StreetRouter router = new StreetRouter(parentNetwork.streetLayer);
        router.distanceLimitMeters = WALK_DISTANCE_LIMIT_METERS;

        // Dominate based on distance in millimeters, since (a) we're using a hard distance limit, and (b) we divide
        // by a speed to get time when we use these tables.
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        router.setOrigin(originVertex);
        router.route();

        // The values in this map will be distances in millimeters since that is our dominance function.
        return router.getReachedVertices();
    }

    public int getStopCount () {
        return stopIdForIndex.size();
    }

    // Mark all services that are active on the given day. Trips on inactive services will not be used in the search.
    public BitSet getActiveServicesForDate (LocalDate date) {
        BitSet activeServices = new BitSet();
        int s = 0;
        for (Service service : services) {
            if (service.activeOn(date)) {
                activeServices.set(s);
            }
            s++;
        }
        return activeServices;
    }

    // TODO setStreetLayer which automatically links and records the streetLayer ID in a field for use elsewhere?


    public TransitLayer clone() {
        try {
            return (TransitLayer) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Get a coordinate for a stop in FIXED POINT DEGREES */
    public Coordinate getCoordinateForStopFixed(int s) {
        int v = streetVertexForStop.get(s);

        if (v == -1) return null;

        VertexStore.Vertex vertex = parentNetwork.streetLayer.vertexStore.getCursor(v);
        return new Coordinate(vertex.getFixedLon(), vertex.getFixedLat());
    }

    /** Get a JTS point for a stop in FIXED POINT DEGREES */
    public Point getJTSPointForStopFixed(int s) {
        Coordinate c = getCoordinateForStopFixed(s);
        return c == null ? null : GeometryUtils.geometryFactory.createPoint(c);
    }

    /**
     * Log some summary information about the contents of the layer that might help with spotting errors or bad data.
     */
    public void summarizeRoutesAndPatterns() {
        System.out.println("Total stops " + stopForIndex.size());
        System.out.println("Total patterns " + tripPatterns.size());
        System.out.println("routeId,patterns,trips,stops");
        Multimap<String, TripPattern> patternsForRoute = HashMultimap.create();
        for (TripPattern pattern : tripPatterns) {
            patternsForRoute.put(pattern.routeId, pattern);
        }
        for (String routeId : patternsForRoute.keySet()) {
            Collection<TripPattern> patterns = patternsForRoute.get(routeId);
            int nTrips = patterns.stream().mapToInt(p -> p.tripSchedules.size()).sum();
            TIntSet stopsUsed = new TIntHashSet();
            for (TripPattern pattern : patterns) stopsUsed.addAll(pattern.stops);
            int nStops = stopsUsed.size();
            int nPatterns = patternsForRoute.get(routeId).size();
            System.out.println(String.join(",", routeId,
                    Integer.toString(nPatterns), Integer.toString(nTrips), Integer.toString(nStops)));
        }
    }

    /** How much information should we load/save? */
    public enum LoadLevel {
        /** Load only information required for analytics, leaving out route names, etc. */
        BASIC,
        /** Load enough information for customer facing trip planning */
        FULL
    }

    public static TransitModes getTransitModes(int routeType) {
        /* TPEG Extension  https://groups.google.com/d/msg/gtfs-changes/keT5rTPS7Y0/71uMz2l6ke0J */
        if (routeType >= 100 && routeType < 200){ // Railway Service
            return TransitModes.RAIL;
        }else if (routeType >= 200 && routeType < 300){ //Coach Service
            return TransitModes.BUS;
        }else if (routeType >= 300 && routeType < 500){ //Suburban Railway Service and Urban Railway service
            return TransitModes.RAIL;
        }else if (routeType >= 500 && routeType < 700){ //Metro Service and Underground Service
            return TransitModes.SUBWAY;
        }else if (routeType >= 700 && routeType < 900){ //Bus Service and Trolleybus service
            return TransitModes.BUS;
        }else if (routeType >= 900 && routeType < 1000){ //Tram service
            return TransitModes.TRAM;
        }else if (routeType >= 1000 && routeType < 1100){ //Water Transport Service
            return TransitModes.FERRY;
        }else if (routeType >= 1100 && routeType < 1200){ //Air Service
            return TransitModes.AIR;
        }else if (routeType >= 1200 && routeType < 1300){ //Ferry Service
            return TransitModes.FERRY;
        }else if (routeType >= 1300 && routeType < 1400){ //Telecabin Service
            return TransitModes.GONDOLA;
        }else if (routeType >= 1400 && routeType < 1500){ //Funicalar Service
            return TransitModes.FUNICULAR;
        }else if (routeType >= 1500 && routeType < 1600){ //Taxi Service
            throw new IllegalArgumentException("Taxi service not supported" + routeType);
        }
        //Is this really needed?
        /**else if (routeType >= 1600 && routeType < 1700){ //Self drive
            return TransitModes.CAR;
        }*/
        /* Original GTFS route types. Should these be checked before TPEG types? */
        switch (routeType) {
        case 0:
            return TransitModes.TRAM;
        case 1:
            return TransitModes.SUBWAY;
        case 2:
            return TransitModes.RAIL;
        case 3:
            return TransitModes.BUS;
        case 4:
            return TransitModes.FERRY;
        case 5:
            return TransitModes.CABLE_CAR;
        case 6:
            return TransitModes.GONDOLA;
        case 7:
            return TransitModes.FUNICULAR;
        default:
            throw new IllegalArgumentException("unknown gtfs route type " + routeType);
        }
    }

    /**
     * @param willBeModified must be true if the scenario to be applied will make any changes to the transit network.
     * @return a semi-shallow copy of this transit layer for use when applying scenarios.
     */
    public TransitLayer scenarioCopy(TransportNetwork newScenarioNetwork, boolean willBeModified) {
        TransitLayer copy = this.clone();
        copy.parentNetwork = newScenarioNetwork;
        if (willBeModified) {
            // Protectively copy all the lists that will be affected by adding new stops to the network.
            // See StopSpec.materializeOne(). We would really only need to do this for modifications that create new stops.
            copy.stopIdForIndex = new ArrayList<>(this.stopIdForIndex);
            copy.stopNames = new ArrayList<>(this.stopNames);
            copy.streetVertexForStop = new TIntArrayList(this.streetVertexForStop);
            copy.stopToVertexDistanceTables = new ArrayList<>(this.stopToVertexDistanceTables);
            copy.transfersForStop = new ArrayList<>(this.transfersForStop);
            copy.routes = new ArrayList<>(this.routes);
            // To indicate that this layer is different than the one it was copied from, record the scenarioId of
            // the scenario that modified it. If the scenario will not affect the contents of the layer, its
            // scenarioId remains unchanged as is done in StreetLayer.
            copy.scenarioId = newScenarioNetwork.scenarioId;
        }
        return copy;
    }

    /**
     * Finds all the transit stops in given envelope and returns it. Stops also have mode which is mode of route in
     * first pattern that this stop is found in. Stop coordinates are jittered
     * {@link com.conveyal.r5.point_to_point.PointToPointRouterServer#jitter(VertexStore.Vertex)}. Meaning they are
     * moved a little from their actual coordinate so that multiple stops at same coordinate can be seen.
     *
     * This will overselect from the spatial index but this is only used for visualizations.
     *
     * @param env Envelope in float degrees
     */
    public Collection<com.conveyal.r5.api.util.Stop> findApiStopsInEnvelope (Envelope env) {
        List<com.conveyal.r5.api.util.Stop> stops = new ArrayList<>();
        EdgeStore.Edge e = this.parentNetwork.streetLayer.edgeStore.getCursor();
        TIntSet nearbyEdges = this.parentNetwork.streetLayer.spatialIndex.query(VertexStore.envelopeToFixed(env));

        nearbyEdges.forEach(eidx -> {
            e.seek(eidx);
            if (e.getFlag(EdgeStore.EdgeFlag.LINK) && stopForStreetVertex.containsKey(e.getFromVertex())) {
                int stopIdx = stopForStreetVertex.get(e.getFromVertex());

                com.conveyal.r5.api.util.Stop stop = new com.conveyal.r5.api.util.Stop(stopIdx, this, true,
                    true);
                stops.add(stop);
            }
            return true;
        });

        return stops;
    }

    /**
     * Find all the transit stops inside the given Geometry. Filtering is performed - this should not overselect.
     * This should include stops added by the scenario, minus those removed by the scenario.
     * @param geometry a JTS geometry in floating point WGS84 degrees
     */
    public TIntSet findStopsInGeometry (Geometry geometry) {
        Envelope envelopeFloating = geometry.getEnvelopeInternal();
        Envelope envelopeFixed = VertexStore.envelopeToFixed(envelopeFloating);
        // This should get all the edges including ones added by a scenario, minus those removed by a scenario.
        TIntSet nearbyEdges = this.parentNetwork.streetLayer.findEdgesInEnvelope(envelopeFixed);
        EdgeStore.Edge e = this.parentNetwork.streetLayer.edgeStore.getCursor();
        VertexStore.Vertex v = this.parentNetwork.streetLayer.vertexStore.getCursor();
        final TIntSet stops = new TIntHashSet();
        nearbyEdges.forEach(eidx -> {
            e.seek(eidx);
            if (e.getFlag(EdgeStore.EdgeFlag.LINK)) {
                int fromStreetVertex = e.getFromVertex();
                if (stopForStreetVertex.containsKey(fromStreetVertex)) {
                    v.seek(fromStreetVertex);
                    if (geometry.contains(v.getJTSPointFloating())) {
                        int stopIdx = stopForStreetVertex.get(fromStreetVertex);
                        stops.add(stopIdx);
                    }
                }
            }
            return true;
        });
        return stops;
    }

}
