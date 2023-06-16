package com.conveyal.gtfs;

import com.conveyal.gtfs.error.GTFSError;
import com.conveyal.gtfs.error.ReferentialIntegrityError;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.model.Fare;
import com.conveyal.gtfs.model.FareAttribute;
import com.conveyal.gtfs.model.FareRule;
import com.conveyal.gtfs.model.FeedInfo;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Shape;
import com.conveyal.gtfs.model.ShapePoint;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Transfer;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.validator.model.Priority;
import com.conveyal.gtfs.validator.service.GeoUtils;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.geotools.referencing.GeodeticCalculator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateList;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.conveyal.gtfs.util.GeometryUtil.geometryFactory;
import static com.conveyal.gtfs.util.Util.human;

/**
 * This is a MapDB-backed representation of the data from a single GTFS feed.
 * All entities are expected to be from a single namespace, do not load several feeds into a single GTFSFeed.
 */
public class GTFSFeed implements Cloneable, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(GTFSFeed.class);
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The MapDB database handling persistence of Maps to a pair of disk files behind the scenes. */
    private DB db;

    /** An ID (sometimes declared by the feed itself) which may remain the same across successive feed versions. */
    public String feedId;

    /**
     * This field was merged in from the wrapper FeedSource. It is a unique identifier for this particular GTFS file.
     * Successive versions of the data for the same operators, or even different copies of the same operator's data
     * uploaded by different people, should have different uniqueIds.
     * In practice this is mostly copied into WrappedGTFSEntity instances used in the Analysis GraphQL API.
     */
    public transient String uniqueId; // set this to feedId until it is overwritten, to match FeedSource behavior

    // All tables below should be MapDB maps so the entire GTFSFeed is persistent and uses constant memory.

    /** Map from agency_id to Agency entity. */
    public final Map<String, Agency> agency;

    /**
     * Map from empty string to FeedInfo entity, this should probably be a set but keeping this representation for
     * compatibility with existing MapDB files.
     */
    public final Map<String, FeedInfo> feedInfo;

    // Sets of tuples are used to make multimaps in mapdb:
    // https://github.com/jankotek/MapDB/blob/release-1.0/src/test/java/examples/MultiMap.java
    /** Multimap from ??? to Frequency entities. */
    public final NavigableSet<Tuple2<String, Frequency>> frequencies;

    /** Map from route_id to Route entity. */
    public final Map<String, Route> routes;

    /** Map from stop_id to Stop entity. */
    public final Map<String, Stop> stops;

    /** Map from ? to ? */
    public final Map<String, Transfer> transfers;

    /** Map from trip_id to Trip entity. */
    public final BTreeMap<String, Trip> trips;

    // FIXME this is this only thing that is a plain map, not in the MapDB
    public final Set<String> transitIds = new HashSet<>();

    /**
     * CRC32 of the GTFS file this was loaded from.
     * FIXME actually it's xored CRC32s, and why are we not just accessing the mapdb atomic long directly?
     */
    public long checksum;

    /** Map from 2-tuples of (shape_id, shape_pt_sequence) to ShapePoint entities. */
    public final ConcurrentNavigableMap<Tuple2<String, Integer>, ShapePoint> shape_points;

    /**
     * Map from 2-tuples of (trip_id, stop_sequence) to StopTime entities. Tuple2's parameter types are not specified
     * because a later function call requires passing Fun.HI (an Object) as a parameter.
     */
    public final BTreeMap<Tuple2, StopTime> stop_times;

    /** A fare is a fare_attribute and all fare_rules that reference that fare_attribute. TODO what is the path? */
    public final Map<String, Fare> fares;

    /** A service is a calendar entry and all calendar_dates that modify that calendar entry. TODO what is the path? */
    public final BTreeMap<String, Service> services;

    /**
     * A place to accumulate errors while the feed is loaded. Tolerate as many errors as possible and keep on loading.
     * Note that this set is in memory, not in the MapDB file. We save these into a JSON file to avoid shuttling
     * all the serialized errors back and forth to workers. Older workers do have a MapDB table here, but when they
     * try to reopen newer MapDB files without that table, even in read-only mode, they'll just receive an empty Set.
     */
    public final Set<GTFSError> errors;

    // TODO eliminate if not used by Analysis
    /** Merged stop buffers polygon built lazily by getMergedBuffers() */
    private transient Geometry mergedBuffers;

    /** Map from pattern IDs to Pattern entities (which are generated by us, not found in GTFS). */
    public final Map<String, Pattern> patterns;

    /** Map from each trip_id to ID of trip pattern containing that trip. */
    public final Map<String, String> patternForTrip;

    /** Once a GTFSFeed has one feed loaded into it, we set this to true to block loading any additional feeds. */
    private boolean loaded = false;

    /**
     * Set this to a ProgressListener to receive updates while loading GTFS. Like OSM files we have no idea how many
     * entities are in the GTFS before we begin, so can only report progress based on the number of bytes read.
     * Unlike OSM, GTFS is a zip file and will usually require random access to read the tables so we report progress
     * on each table separately. This does give nice fine-grained reporting for the larger tables like stop_times.
     */
    public ProgressListener progressListener = null;

    /**
     * The order in which we load the tables is important for two reasons.
     * 1. We must load feed_info first so we know the feed ID before loading any other entities. This could be relaxed
     * by having entities point to the feed object rather than its ID String.
     * 2. Referenced entities must be loaded before any entities that reference them. This is because we check
     * referential integrity while the files are being loaded. This is done on the fly during loading because it allows
     * us to associate a line number with errors in objects that don't have any other clear identifier.
     *
     * Interestingly, all references are resolvable when tables are loaded in alphabetical order.
     *
     * @param zip the source ZIP file to load, which will be closed when done loading.
     * @param fid the feedId to be set on the feed. If null, any feedId declared in the feed will be used, falling back
     *            on the filename without any .zip extension.
     */
    public void loadFromFile(ZipFile zip, String fid) throws Exception {
        if (this.loaded) throw new UnsupportedOperationException("Attempt to load GTFS into existing database");

        // NB we don't have a single CRC for the file, so we combine all the CRCs of the component files. NB we are not
        // simply summing the CRCs because CRCs are (I assume) uniformly randomly distributed throughout the width of a
        // long, so summing them is a convolution which moves towards a Gaussian with mean 0 (i.e. more concentrated
        // probability in the center), degrading the quality of the hash. Instead we XOR. Assuming each bit is independent,
        // this will yield a nice uniformly distributed result, because when combining two bits there is an equal
        // probability of any input, which means an equal probability of any output. At least I think that's all correct.
        // Repeated XOR is not commutative but zip.stream returns files in the order they are in the central directory
        // of the zip file, so that's not a problem.
        checksum = zip.stream().mapToLong(ZipEntry::getCrc).reduce((l1, l2) -> l1 ^ l2).getAsLong();

        db.getAtomicLong("checksum").set(checksum);

        new FeedInfo.Loader(this).loadTable(zip);
        // maybe we should just point to the feed object itself instead of its ID, and null out its stoptimes map after loading
        if (fid != null) {
            feedId = fid;
            LOG.info("Forcing feedId in MapDB to supplied string: {}.", feedId);
        } else if (feedId == null || feedId.isEmpty()) {
            feedId = new File(zip.getName()).getName().replaceAll("\\.zip$", "");
            LOG.info("No feedId supplied and feed does not declare one. Using file name {}.", feedId);
        } else {
            LOG.info("Feed ID is '{}'.", feedId);
        }

        db.getAtomicString("feed_id").set(feedId);

        new Agency.Loader(this).loadTable(zip);

        // calendars and calendar dates are joined into services. This means a lot of manipulating service objects as
        // they are loaded; since mapdb keys/values are immutable, load them in memory then copy them to MapDB once
        // we're done loading them
        {
            Map<String, Service> serviceTable = new HashMap<>();
            new Calendar.Loader(this, serviceTable).loadTable(zip);
            new CalendarDate.Loader(this, serviceTable).loadTable(zip);
            this.services.putAll(serviceTable);
            // Joined Services have been persisted to MapDB. In-memory HashMap goes out of scope for garbage collection.
        }

        // Joining is performed for Fares as for Services above.
        {
            Map<String, Fare> fares = new HashMap<>();
            new FareAttribute.Loader(this, fares).loadTable(zip);
            new FareRule.Loader(this, fares).loadTable(zip);
            this.fares.putAll(fares);
            // Joined Fares have been persisted to MapDB. In-memory HashMap goes out of scope for garbage collection.
        }

        // Comment out the StopTime and/or ShapePoint loaders for quick testing on large feeds.
        new Route.Loader(this).loadTable(zip);
        new ShapePoint.Loader(this).loadTable(zip);
        new Stop.Loader(this).loadTable(zip);
        new Transfer.Loader(this).loadTable(zip);
        new Trip.Loader(this).loadTable(zip);
        new Frequency.Loader(this).loadTable(zip);
        new StopTime.Loader(this).loadTable(zip);
        zip.close();

        // There are conceivably cases where the extra step of identifying and naming patterns is not necessary.
        // In current usage we do always need them, and performing this step during load allows enforcing subsequent
        // read-only access.
        // Find patterns only if there are no referential integrity errors or other severe problems. Those problems
        // can cause pattern finding to fail hard with null pointer exceptions, causing detailed error messages to be
        // lost and hiding underlying problems from the user. If high-priority problems are present, the feed should be
        // presented to the user as unuseable anyway.
        if (errors.stream().anyMatch(e -> e.getPriority() == Priority.HIGH)) {
            LOG.warn("Feed contains high priority errors, not finding patterns. It will be useless for routing.");
        } else {
            findPatterns();
        }

        // Prevent loading additional feeds into this MapDB.
        loaded = true;
        LOG.info("Detected {} errors in feed.", errors.size());
    }

    public void toFile (String file) {
        try {
            File out = new File(file);
            OutputStream os = new FileOutputStream(out);
            ZipOutputStream zip = new ZipOutputStream(os);

            // write everything
            // TODO: shapes

            // don't write empty feed_info.txt
            if (!this.feedInfo.isEmpty()) new FeedInfo.Writer(this).writeTable(zip);

            new Agency.Writer(this).writeTable(zip);
            new Calendar.Writer(this).writeTable(zip);
            new CalendarDate.Writer(this).writeTable(zip);
            new FareAttribute.Writer(this).writeTable(zip);
            new FareRule.Writer(this).writeTable(zip);
            new Frequency.Writer(this).writeTable(zip);
            new Route.Writer(this).writeTable(zip);
            new Stop.Writer(this).writeTable(zip);
            new ShapePoint.Writer(this).writeTable(zip);
            new Transfer.Writer(this).writeTable(zip);
            new Trip.Writer(this).writeTable(zip);
            new StopTime.Writer(this).writeTable(zip);

            zip.close();

            LOG.info("GTFS file written");
        } catch (Exception e) {
            LOG.error("Error saving GTFS: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * For the given trip ID, fetch all the stop times in order of increasing stop_sequence.
     * This is an efficient iteration over a tree map.
     */
    public Iterable<StopTime> getOrderedStopTimesForTrip (String trip_id) {
        Map<Fun.Tuple2, StopTime> tripStopTimes =
                stop_times.subMap(
                        Fun.t2(trip_id, null),
                        Fun.t2(trip_id, Fun.HI)
                );
        return tripStopTimes.values();
    }

    /** Get the shape for the given shape ID */
    public Shape getShape (String shape_id) {
        Shape shape = new Shape(this, shape_id);
        return shape.shape_dist_traveled.length > 0 ? shape : null;
    }

    /**
     * For the given trip ID, fetch all the stop times in order, and interpolate stop-to-stop travel times.
     */
    public Iterable<StopTime> getInterpolatedStopTimesForTrip (String trip_id) throws FirstAndLastStopsDoNotHaveTimes {
        // clone stop times so as not to modify base GTFS structures
        StopTime[] stopTimes = StreamSupport.stream(getOrderedStopTimesForTrip(trip_id).spliterator(), false)
                .map(st -> st.clone())
                .toArray(i -> new StopTime[i]);

        // avoid having to make sure that the array has length below.
        if (stopTimes.length == 0) return Collections.emptyList();

        // first pass: set all partially filled stop times
        for (StopTime st : stopTimes) {
            if (st.arrival_time != Entity.INT_MISSING && st.departure_time == Entity.INT_MISSING) {
                st.departure_time = st.arrival_time;
            }

            if (st.arrival_time == Entity.INT_MISSING && st.departure_time != Entity.INT_MISSING) {
                st.arrival_time = st.departure_time;
            }
        }

        // quick check: ensure that first and last stops have times.
        // technically GTFS requires that both arrival_time and departure_time be filled at both the first and last stop,
        // but we are slightly more lenient and only insist that one of them be filled at both the first and last stop.
        // The meaning of the first stop's arrival time is unclear, and same for the last stop's departure time (except
        // in the case of interlining).

        // it's fine to just check departure time, as the above pass ensures that all stop times have either both
        // arrival and departure times, or neither
        if (stopTimes[0].departure_time == Entity.INT_MISSING || stopTimes[stopTimes.length - 1].departure_time == Entity.INT_MISSING) {
            throw new FirstAndLastStopsDoNotHaveTimes();
        }

        // second pass: fill complete stop times
        int startOfInterpolatedBlock = -1;
        for (int stopTime = 0; stopTime < stopTimes.length; stopTime++) {

            if (stopTimes[stopTime].departure_time == Entity.INT_MISSING && startOfInterpolatedBlock == -1) {
                startOfInterpolatedBlock = stopTime;
            }
            else if (stopTimes[stopTime].departure_time != Entity.INT_MISSING && startOfInterpolatedBlock != -1) {
                // we have found the end of the interpolated section
                int nInterpolatedStops = stopTime - startOfInterpolatedBlock;
                double totalLengthOfInterpolatedSection = 0;
                double[] lengthOfInterpolatedSections = new double[nInterpolatedStops];

                GeodeticCalculator calc = new GeodeticCalculator();

                for (int stopTimeToInterpolate = startOfInterpolatedBlock, i = 0; stopTimeToInterpolate < stopTime; stopTimeToInterpolate++, i++) {
                    Stop start = stops.get(stopTimes[stopTimeToInterpolate - 1].stop_id);
                    Stop end = stops.get(stopTimes[stopTimeToInterpolate].stop_id);
                    calc.setStartingGeographicPoint(start.stop_lon, start.stop_lat);
                    calc.setDestinationGeographicPoint(end.stop_lon, end.stop_lat);
                    double segLen = calc.getOrthodromicDistance();
                    totalLengthOfInterpolatedSection += segLen;
                    lengthOfInterpolatedSections[i] = segLen;
                }

                // add the segment post-last-interpolated-stop
                Stop start = stops.get(stopTimes[stopTime - 1].stop_id);
                Stop end = stops.get(stopTimes[stopTime].stop_id);
                calc.setStartingGeographicPoint(start.stop_lon, start.stop_lat);
                calc.setDestinationGeographicPoint(end.stop_lon, end.stop_lat);
                totalLengthOfInterpolatedSection += calc.getOrthodromicDistance();

                int departureBeforeInterpolation = stopTimes[startOfInterpolatedBlock - 1].departure_time;
                int arrivalAfterInterpolation = stopTimes[stopTime].arrival_time;
                int totalTime = arrivalAfterInterpolation - departureBeforeInterpolation;

                double lengthSoFar = 0;
                for (int stopTimeToInterpolate = startOfInterpolatedBlock, i = 0; stopTimeToInterpolate < stopTime; stopTimeToInterpolate++, i++) {
                    lengthSoFar += lengthOfInterpolatedSections[i];

                    int time = (int) (departureBeforeInterpolation + totalTime * (lengthSoFar / totalLengthOfInterpolatedSection));
                    stopTimes[stopTimeToInterpolate].arrival_time = stopTimes[stopTimeToInterpolate].departure_time = time;
                }

                // we're done with this block
                startOfInterpolatedBlock = -1;
            }
        }

        return Arrays.asList(stopTimes);
    }

    public Collection<Frequency> getFrequencies (String trip_id) {
        // IntelliJ tells me all these casts are unnecessary, and that's also my feeling, but the code won't compile
        // without them
        return (List<Frequency>) frequencies.subSet(new Fun.Tuple2(trip_id, null), new Fun.Tuple2(trip_id, Fun.HI)).stream()
                .map(t2 -> ((Tuple2<String, Frequency>) t2).b)
                .collect(Collectors.toList());
    }

    public List<String> getOrderedStopListForTrip (String trip_id) {
        Iterable<StopTime> orderedStopTimes = getOrderedStopTimesForTrip(trip_id);
        List<String> stops = Lists.newArrayList();
        // In-order traversal of StopTimes within this trip. The 2-tuple keys determine ordering.
        for (StopTime stopTime : orderedStopTimes) {
            stops.add(stopTime.stop_id);
        }
        return stops;
    }

    /**
     * Bin all trips by stop sequence and pick/drop sequences.
     * A map from a list of stop IDs to a list of Trip IDs that visit those stops in that sequence.
     * This changes the contents of the GTFSFeed (writes to it) so should be done once when the feed is first loaded.
     * In normal usage this will be called automatically at the end of the feed loading process.
     * The method is only public for special cases like tests where we programmatically build feeds.
     */
    public void findPatterns() {
        if (this.patterns.size() > 0) {
            throw new GtfsLibException("Patterns should only be found once, after all trips are loaded.");
        }
        if (progressListener != null) progressListener.beginTask("Grouping trips into patterns.", trips.size());
        int n = 0;
        Multimap<TripPatternKey, String> tripsForPattern = HashMultimap.create();
        for (String trip_id : trips.keySet()) {
            if (++n % 100000 == 0) {
                LOG.info("trip {}", human(n));
            }

            Trip trip = trips.get(trip_id);

            // no need to scope ID here, this is in the context of a single object
            TripPatternKey key = new TripPatternKey(trip.route_id);

            StreamSupport.stream(getOrderedStopTimesForTrip(trip_id).spliterator(), false)
                    .forEach(key::addStopTime);

            tripsForPattern.put(key, trip_id);
            if (progressListener != null) progressListener.increment();
        }

        // create an in memory list because we will rename them and they need to be immutable once they hit mapdb
        List<Pattern> patterns = tripsForPattern.asMap().entrySet()
                .stream()
                .map((e) -> new Pattern(this, e.getKey().stops, new ArrayList<>(e.getValue())))
                .collect(Collectors.toList());

        if (progressListener != null) progressListener.beginTask("Naming and indexing patterns.", patterns.size() * 3);
        namePatterns(patterns);

        // Index patterns by ID and by the trips they contain.
        for (Pattern pattern : patterns) {
            this.patterns.put(pattern.pattern_id, pattern);
            for (String tripid : pattern.associatedTrips) {
                this.patternForTrip.put(tripid, pattern.pattern_id);
            }
            if (progressListener != null) progressListener.increment();
        }

        LOG.info("Total patterns: {}", tripsForPattern.keySet().size());
    }

    /** destructively rename passed in patterns */
    private void namePatterns(Collection<Pattern> patterns) {
        LOG.info("Generating unique names for patterns");

        Map<String, PatternNamingInfo> namingInfoForRoute = new HashMap<>();

        for (Pattern pattern : patterns) {
            if (pattern.associatedTrips.isEmpty() || pattern.orderedStops.isEmpty()) continue;

            Trip trip = trips.get(pattern.associatedTrips.get(0));

            // TODO this assumes there is only one route associated with a pattern
            String route = trip.route_id;

            // names are unique at the route level
            if (!namingInfoForRoute.containsKey(route)) namingInfoForRoute.put(route, new PatternNamingInfo());
            PatternNamingInfo namingInfo = namingInfoForRoute.get(route);

            if (trip.trip_headsign != null)
                namingInfo.headsigns.put(trip.trip_headsign, pattern);

            // use stop names not stop IDs as stops may have duplicate names and we want unique pattern names
            String fromName = stops.get(pattern.orderedStops.get(0)).stop_name;
            String toName = stops.get(pattern.orderedStops.get(pattern.orderedStops.size() - 1)).stop_name;

            namingInfo.fromStops.put(fromName, pattern);
            namingInfo.toStops.put(toName, pattern);

            for (String stopId : pattern.orderedStops) {
                Stop stop = stops.get(stopId);
                if (stop == null) {
                    // A ReferentialIntegrityError should have been recorded during stop_time loading and naming should
                    // be halted.
                   return;
                }
                if (fromName.equals(stop.stop_name) || toName.equals(stop.stop_name)) {
                    continue;
                }
                namingInfo.vias.put(stop.stop_name, pattern);
            }
            namingInfo.patternsOnRoute.add(pattern);
            if (progressListener != null) progressListener.increment();

        }

        // name the patterns on each route
        for (PatternNamingInfo info : namingInfoForRoute.values()) {
            for (Pattern pattern : info.patternsOnRoute) {
                pattern.name = null; // clear this now so we don't get confused later on

                String fromName = stops.get(pattern.orderedStops.get(0)).stop_name;
                String toName = stops.get(pattern.orderedStops.get(pattern.orderedStops.size() - 1)).stop_name;

                // check if combination from, to is unique
                Set<Pattern> intersection = new HashSet<>(info.fromStops.get(fromName));
                intersection.retainAll(info.toStops.get(toName));

                if (intersection.size() == 1) {
                    pattern.name = String.format(Locale.US, "from %s to %s", fromName, toName);
                    continue;
                }

                // check for unique via stop
                pattern.orderedStops.stream().map(stops::get).forEach(stop -> {
                    Set<Pattern> viaIntersection = new HashSet<>(intersection);
                    viaIntersection.retainAll(info.vias.get(stop.stop_name));

                    if (viaIntersection.size() == 1) {
                        pattern.name = String.format(Locale.US, "from %s to %s via %s", fromName, toName, stop.stop_name);
                    }
                });

                if (pattern.name == null) {
                    // no unique via, one pattern is subset of other.
                    if (intersection.size() == 2) {
                        Iterator<Pattern> it = intersection.iterator();
                        Pattern p0 = it.next();
                        Pattern p1 = it.next();

                        if (p0.orderedStops.size() > p1.orderedStops.size()) {
                            p1.name = String.format(Locale.US, "from %s to %s express", fromName, toName);
                            p0.name = String.format(Locale.US, "from %s to %s local", fromName, toName);
                        } else if (p1.orderedStops.size() > p0.orderedStops.size()){
                            p0.name = String.format(Locale.US, "from %s to %s express", fromName, toName);
                            p1.name = String.format(Locale.US, "from %s to %s local", fromName, toName);
                        }
                    }
                }

                if (pattern.name == null) {
                    // give up
                    pattern.name = String.format(Locale.US, "from %s to %s like trip %s", fromName, toName, pattern.associatedTrips.get(0));
                }
                if (progressListener != null) progressListener.increment();
            }

            // attach a stop and trip count to each
            for (Pattern pattern : info.patternsOnRoute) {
                pattern.name = String.format(Locale.US, "%s stops %s (%s trips)",
                                pattern.orderedStops.size(), pattern.name, pattern.associatedTrips.size());
            }
        }
    }

    public LineString getStraightLineForStops(String trip_id) {
        CoordinateList coordinates = new CoordinateList();
        LineString lineString = null;
        Trip trip = trips.get(trip_id);

        Iterable<StopTime> stopTimes;
        stopTimes = getOrderedStopTimesForTrip(trip.trip_id);
        // lineString must remain null if there are less than two stopTimes to avoid
        // an exception when creating linestring.
        if (Iterables.size(stopTimes) > 1) {
            for (StopTime stopTime : stopTimes) {
                Stop stop = stops.get(stopTime.stop_id);
                Double lat = stop.stop_lat;
                Double lon = stop.stop_lon;
                coordinates.add(new Coordinate(lon, lat));
            }
            lineString = geometryFactory.createLineString(coordinates.toCoordinateArray());
        }
        return lineString;
    }

    /**
     * Returns a trip geometry object (LineString) for a given trip id.
     * If the trip has a shape reference, this will be used for the geometry.
     * Otherwise, the ordered stoptimes will be used.
     *
     * @param   trip_id   trip id of desired trip geometry
     * @return          the LineString representing the trip geometry.
     * @see             LineString
     */
    public LineString getTripGeometry(String trip_id){

        CoordinateList coordinates = new CoordinateList();
        LineString ls = null;
        Trip trip = trips.get(trip_id);

        // If trip has shape_id, use it to generate geometry.
        if (trip.shape_id != null) {
            Shape shape = getShape(trip.shape_id);
            if (shape != null) ls = shape.geometry;
        }

        // Use the ordered stoptimes.
        if (ls == null) {
            ls = getStraightLineForStops(trip_id);
        }

        return ls;
    }

    /** Get the length of a trip in meters. */
    public double getTripDistance (String trip_id, boolean straightLine) {
        return straightLine
                ? GeoUtils.getDistance(this.getStraightLineForStops(trip_id))
                : GeoUtils.getDistance(this.getTripGeometry(trip_id));
    }

    /** Get trip speed (using trip shape if available) in meters per second. */
    public double getTripSpeed (String trip_id) {
        return getTripSpeed(trip_id, false);
    }

    /** Get trip speed in meters per second. */
    public double getTripSpeed (String trip_id, boolean straightLine) {

        StopTime firstStopTime = this.stop_times.ceilingEntry(Fun.t2(trip_id, null)).getValue();
        StopTime lastStopTime = this.stop_times.floorEntry(Fun.t2(trip_id, Fun.HI)).getValue();

        // ensure that stopTime returned matches trip id (i.e., that the trip has stoptimes)
        if (!firstStopTime.trip_id.equals(trip_id) || !lastStopTime.trip_id.equals(trip_id)) {
            return Double.NaN;
        }

        double distance = getTripDistance(trip_id, straightLine);

        // trip time (in seconds)
        int time = lastStopTime.arrival_time - firstStopTime.departure_time;

        return distance / time; // meters per second
    }

    // FIXME this is only adding the first and last dates of each calendar, but that's enough for finding the range.
    // TODO reimplement returning service density by mode per day?
    public List<LocalDate> getDatesOfService () {
        List<LocalDate> serviceDates = new ArrayList<>();
        for (Service service : this.services.values()) {
            if (service.calendar != null) {
                serviceDates.add(LocalDate.from(dateFormatter.parse(Integer.toString(service.calendar.start_date))));
                serviceDates.add(LocalDate.from(dateFormatter.parse(Integer.toString(service.calendar.end_date))));
            }
            for (CalendarDate calendarDate : service.calendar_dates.values()) {
                // This predicate should really be an instance method on CalendarDate, as it recurs in multiple places.
                if (calendarDate.exception_type == 1) {
                    serviceDates.add(calendarDate.date);
                }
            }
        }
        return serviceDates;
    }

    // TODO: code review
    public Geometry getMergedBuffers() {
        if (this.mergedBuffers == null) {
//            synchronized (this) {
                Collection<Geometry> polygons = new ArrayList<>();
                for (Stop stop : this.stops.values()) {
                    if (stop.stop_lat > -1 && stop.stop_lat < 1 || stop.stop_lon > -1 && stop.stop_lon < 1) {
                        continue;
                    }
                    Point stopPoint = geometryFactory.createPoint(new Coordinate(stop.stop_lon, stop.stop_lat));
                    Polygon stopBuffer = (Polygon) stopPoint.buffer(.01);
                    polygons.add(stopBuffer);
                }
                Geometry multiGeometry = geometryFactory.buildGeometry(polygons);
                this.mergedBuffers = multiGeometry.union();
                if (polygons.size() > 100) {
                    this.mergedBuffers = DouglasPeuckerSimplifier.simplify(this.mergedBuffers, .001);
                }
//            }
        }
        return this.mergedBuffers;
    }

    /**
     * Cloning can be useful when you want to make only a few modifications to an existing feed.
     * Keep in mind that this is a shallow copy, so you'll have to create new maps in the clone for tables you want
     * to modify.
     */
    @Override
    public GTFSFeed clone() {
        try {
            return (GTFSFeed) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    protected void finalize() throws IOException {
        // Although everyone recommends against using finalizers to take actions, as far as I know they are a good place
        // to assert that cleanup actions were taken before an object went out of scope.
        if (db != null && !db.isClosed()) {
            LOG.error("MapDB database was not closed before it was garbage collected. This is a bug!");
        }
    }

    public void close () {
        db.close();
    }

    /** Thrown when we cannot interpolate stop times because the first or last stops do not have times */
    public class FirstAndLastStopsDoNotHaveTimes extends Exception {
        /** do nothing */
    }

    /**
     * holds information about pattern names on a particular route,
     * modeled on https://github.com/opentripplanner/OpenTripPlanner/blob/master/src/main/java/org/opentripplanner/routing/edgetype/TripPattern.java#L379
     */
    private static class PatternNamingInfo {
        Multimap<String, Pattern> headsigns = HashMultimap.create();
        Multimap<String, Pattern> fromStops = HashMultimap.create();
        Multimap<String, Pattern> toStops = HashMultimap.create();
        Multimap<String, Pattern> vias = HashMultimap.create();
        List<Pattern> patternsOnRoute = new ArrayList<>();
    }


    /// CONSTRUCTORS and associated helper methods
    /// These are private, use static factory methods to create instances.

    /** @param dbFile the file to create or connect to, or null if a temporary file should be used. */
    private GTFSFeed (File dbFile, boolean writable) {
        this(constructMapDb(dbFile, writable));
    }

    private GTFSFeed (DB db) {
        this.db = db;

        agency = db.getTreeMap("agency");
        feedInfo = db.getTreeMap("feed_info");
        routes = db.getTreeMap("routes");
        trips = db.getTreeMap("trips");
        stop_times = db.getTreeMap("stop_times");
        frequencies = db.getTreeSet("frequencies");
        transfers = db.getTreeMap("transfers");
        stops = db.getTreeMap("stops");
        fares = db.getTreeMap("fares");
        services = db.getTreeMap("services");
        shape_points = db.getTreeMap("shape_points");

        // Note that the feedId and checksum fields are manually read in and out of entries in the MapDB, rather than
        // the class fields themselves being of type Atomic.String and Atomic.Long. This avoids any locking and
        // MapDB retrieval overhead for these fields which are used very frequently.
        feedId = db.getAtomicString("feed_id").get();
        checksum = db.getAtomicLong("checksum").get();

        // use Java serialization because MapDB serialization is very slow with JTS as they have a lot of references.
        // nothing else contains JTS objects
        patterns = db.createTreeMap("patterns")
                .valueSerializer(Serializer.JAVA)
                .makeOrGet();

        patternForTrip = db.getTreeMap("patternForTrip");

        // Note that this is an in-memory Java HashSet instead of MapDB table (as it was in past versions).
        errors = new HashSet<>();
    }

    // One critical point when constructing the MapDB is the instance cache type and size.
    // The instance cache is how MapDB keeps some instances in memory to avoid deserializing them repeatedly from disk.
    // We perform referential integrity checks against tables which in some feeds have hundreds of thousands of rows.
    // We have observed that the referential integrity checks are very slow with the instance cache disabled.
    // MapDB's default cache type is a hash table, which is very sensitive to the cache size.
    // It defaults to 2^15 (32ki) and only seems to run smoothly at other powers of two, so we use 2^16 (64ki).
    // This might have something to do with compiler optimizations on the hash code calculations.
    // Initial tests show similar speeds for the default hashtable cache of 64k or 32k size and the hardRef cache.
    // By not calling any of the cacheEnable or cacheSize methods on the DB builder, we use the default values
    // that seem to perform well.
    private static DB constructMapDb (File dbFile, boolean writable) {
        DBMaker dbMaker;
        // TODO also allow for in-memory
        if (dbFile == null) {
            dbMaker = DBMaker.newTempFileDB();
        } else {
            dbMaker = DBMaker.newFileDB(dbFile);
        }
        if (writable) {
            dbMaker.asyncWriteEnable();
        } else {
            dbMaker.readOnly();
        }
        try{
            return dbMaker
                    .transactionDisable()
                    .mmapFileEnable()
                    .compressionEnable()
                    .closeOnJvmShutdown()
                    .make();
        } catch (Exception e) {
            throw new GtfsLibException("Could not construct db.", e);
        }
    }


    /// STATIC FACTORY METHODS
    /// Use these rather than constructors to create GTFSFeed objects in a more fluent way.


    public static GTFSFeed reopenReadOnly (File file) {
        if (file.exists()) {
            return new GTFSFeed(file, false);
        } else {
            throw new GtfsLibException("Cannot reopen file, it does not exist.");
        }
    }

    /**
     * Create a new DB file and load the specified GTFS ZIP into it. The resulting writable feed object is not returned
     * and must be reopened for subsequent read-only access.
     * @param dbFile the new file in which to store the database, or null to use a temporary file
     * @param feedId the feedId to be set on the feed. If null, any feedId declared in the feed will be used, falling
     *               back on the filename without its .zip extension.
     */
    public static void newFileFromGtfs (File dbFile, File gtfsFile, String feedId) {
        if (gtfsFile == null || !gtfsFile.exists()) {
            throw new GtfsLibException("Cannot load from GTFS feed, file does not exist.");
        }
        try {
            GTFSFeed feed = newWritableFile(dbFile);
            feed.loadFromFile(new ZipFile(gtfsFile), feedId);
            feed.close();
        } catch (Exception e) {
            throw new GtfsLibException("Cannot load GTFS from feed ZIP.", e);
        }
    }

    /**
     * Ideally we wouldn't expose any readable feeds after loading them, but we need to inject progress listeners, clear
     * out errors, and build some indexes. Be sure to close feeds and reopen them read-only as early as is feasible.
     * Ideally we'd somehow encapsulate all that so we never reveal a writable GTFSFeed object.
     * @param dbFile the new database file (which must be empty or not yet exist), or null to use a new temp file.
     */
    public static GTFSFeed newWritableFile (File dbFile) {
        // Length check is for cases where a newly created empty temp file is passed in.
        if (dbFile != null && dbFile.exists() && dbFile.length() > 0) {
            throw new GtfsLibException("Cannot create new file, it already exists.");
        }
        return new GTFSFeed(dbFile, true);
    }

    /**
     * Static factory method returning a new instance of GTFSFeed containing the contents of
     * the GTFS file at the supplied filesystem path. This could probably be combined with some other factory methods.
     */
    public static GTFSFeed writableTempFileFromGtfs (String file) {
        GTFSFeed feed = new GTFSFeed(null, true);
        try {
            ZipFile zip = new ZipFile(file);
            feed.loadFromFile(zip, null);
            zip.close();
            return feed;
        } catch (Exception e) {
            LOG.error("Error loading GTFS: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // NOTE the feedId within the MapDB created here will be the one declared by the feed or based on its filename.
    // This method makes no effort to impose the more unique feed IDs created by the Analysis backend.
    public static GTFSFeed readOnlyTempFileFromGtfs (String fileName) {
        try {
            File tempFile = File.createTempFile("com.conveyal.gtfs.", ".db");
            tempFile.deleteOnExit();
            GTFSFeed.newFileFromGtfs(tempFile, new File(fileName), null);
            return GTFSFeed.reopenReadOnly(tempFile);
        } catch (Exception e) {
            throw new GtfsLibException("Error loading GTFS.", e);
        }
    }

    public static GTFSFeed newWritableInMemory () {
        return new GTFSFeed(DBMaker.newMemoryDB().transactionDisable().make());
    }

}
