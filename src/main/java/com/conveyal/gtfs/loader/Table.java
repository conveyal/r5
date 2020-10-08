package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.model.FareAttribute;
import com.conveyal.gtfs.model.FareRule;
import com.conveyal.gtfs.model.FeedInfo;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.ShapePoint;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Transfer;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static com.conveyal.gtfs.loader.Requirement.EXTENSION;
import static com.conveyal.gtfs.loader.Requirement.OPTIONAL;
import static com.conveyal.gtfs.loader.Requirement.REQUIRED;
import static com.conveyal.gtfs.loader.Requirement.UNKNOWN;


/**
 * This groups a table name with a description of the fields in the table.
 * It can be normative (expressing the specification for a CSV table in GTFS)
 * or descriptive (providing the schema of an RDBMS table).
 *
 * TODO associate Table with EntityPopulator (combine read and write sides)
 */
public class Table {

    private static final Logger LOG = LoggerFactory.getLogger(Table.class);

    final String name;

    final Class<? extends Entity> entityClass;

    final Requirement required;

    final Field[] fields;

    public static final Table AGENCY = new Table("agency", Agency.class, REQUIRED,
        new StringField("agency_id",  OPTIONAL), // FIXME? only required if there are more than one
        new StringField("agency_name",  REQUIRED),
        new URLField("agency_url",  REQUIRED),
        new StringField("agency_timezone",  REQUIRED), // FIXME new field type for time zones?
        new StringField("agency_lang", OPTIONAL), // FIXME new field type for languages?
        new StringField("agency_phone",  OPTIONAL),
        new URLField("agency_fare_url",  OPTIONAL),
        new StringField("agency_email",  OPTIONAL) // FIXME new field type for emails?
    );

    // The GTFS spec says this table is required, but in practice it is not required if calendar_dates is present.
    public static final Table CALENDAR = new Table("calendar", Calendar.class, OPTIONAL,
        new StringField("service_id",  REQUIRED),
        new BooleanField("monday", REQUIRED),
        new BooleanField("tuesday", REQUIRED),
        new BooleanField("wednesday", REQUIRED),
        new BooleanField("thursday", REQUIRED),
        new BooleanField("friday", REQUIRED),
        new BooleanField("saturday", REQUIRED),
        new BooleanField("sunday", REQUIRED),
        new DateField("start_date", REQUIRED), // FIXME New field type for dates? Split string and check each part.
        new DateField("end_date", REQUIRED)
    );

    public static final Table CALENDAR_DATES = new Table("calendar_dates", CalendarDate.class, OPTIONAL,
        new StringField("service_id", REQUIRED),
        new IntegerField("date", REQUIRED),
        new IntegerField("exception_type", REQUIRED, 1, 2)
    );

    public static final Table FARE_ATTRIBUTES = new Table("fare_attributes", FareAttribute.class, OPTIONAL,
        new StringField("fare_id", REQUIRED),
        new DoubleField("price", REQUIRED, 0.0, Double.MAX_VALUE),
        new CurrencyField("currency_type", REQUIRED),
        new ShortField("payment_method", REQUIRED, 1),
        new ShortField("transfers", REQUIRED, 2),
        new IntegerField("transfer_duration", OPTIONAL)
    );


    public static final Table FARE_RULES = new Table("fare_rules", FareRule.class, OPTIONAL,
        new StringField("fare_id", REQUIRED),
        new StringField("route_id", OPTIONAL),
        new StringField("origin_id", OPTIONAL),
        new StringField("destination_id", OPTIONAL),
        new StringField("contains_id", OPTIONAL)
    );

    public static final Table FEED_INFO = new Table("feed_info", FeedInfo.class, OPTIONAL,
        new StringField("feed_publisher_name", REQUIRED),
        new StringField("feed_publisher_url", REQUIRED),
        new LanguageField("feed_lang", REQUIRED),
        new DateField("feed_start_date", OPTIONAL),
        new DateField("feed_end_date", OPTIONAL),
        new StringField("feed_version", OPTIONAL)

    );

    public static final Table FREQUENCIES = new Table("frequencies", Frequency.class, OPTIONAL,
        new StringField("trip_id", REQUIRED),
        new TimeField("start_time", REQUIRED),
        new TimeField("end_time", REQUIRED),
        new IntegerField("headway_secs", REQUIRED, 20, 60*60*2),
        new IntegerField("exact_times", OPTIONAL, 1)
    );

    public static final Table ROUTES = new Table("routes", Route.class, REQUIRED,
        new StringField("route_id",  REQUIRED),
        new StringField("agency_id",  OPTIONAL),
        new StringField("route_short_name",  OPTIONAL), // one of short or long must be provided
        new StringField("route_long_name",  OPTIONAL),
        new StringField("route_desc",  OPTIONAL),
        new IntegerField("route_type", REQUIRED, 999),
        new URLField("route_url",  OPTIONAL),
        new ColorField("route_color",  OPTIONAL), // really this is an int in hex notation
        new ColorField("route_text_color",  OPTIONAL)
    );

    public static final Table SHAPES = new Table("shapes", ShapePoint.class, OPTIONAL,
            new StringField("shape_id", REQUIRED),
            new IntegerField("shape_pt_sequence", REQUIRED),
            new DoubleField("shape_pt_lat", REQUIRED, -80, 80),
            new DoubleField("shape_pt_lon", REQUIRED, -180, 180),
            new DoubleField("shape_dist_traveled", REQUIRED, 0, Double.POSITIVE_INFINITY)
    );

    public static final Table STOPS = new Table("stops", Stop.class, REQUIRED,
        new StringField("stop_id",  REQUIRED),
        new StringField("stop_code",  OPTIONAL),
        new StringField("stop_name",  REQUIRED),
        new StringField("stop_desc",  OPTIONAL),
        new DoubleField("stop_lat", REQUIRED, -80, 80),
        new DoubleField("stop_lon", REQUIRED, -180, 180),
        new StringField("zone_id",  OPTIONAL),
        new URLField("stop_url",  OPTIONAL),
        new ShortField("location_type", OPTIONAL, 2),
        new StringField("parent_station",  OPTIONAL),
        new StringField("stop_timezone",  OPTIONAL),
        new ShortField("wheelchair_boarding", OPTIONAL, 1)
    );

    public static final Table STOP_TIMES = new Table("stop_times", StopTime.class, REQUIRED,
            new StringField("trip_id", REQUIRED),
            new IntegerField("stop_sequence", REQUIRED),
            new StringField("stop_id", REQUIRED),
            // TODO verify that we have a special check for arrival and departure times first and last stop_time in a trip, which are reqiured
            new TimeField("arrival_time", OPTIONAL),
            new TimeField("departure_time", OPTIONAL),
            new StringField("stop_headsign", OPTIONAL),
            new ShortField("pickup_type", OPTIONAL, 2),
            new ShortField("drop_off_type", OPTIONAL, 2),
            new DoubleField("shape_dist_traveled", OPTIONAL, 0, Double.POSITIVE_INFINITY),
            new ShortField("timepoint", OPTIONAL, 1),
            new IntegerField("fare_units_traveled", EXTENSION) // OpenOV NL extension
    );

    public static final Table TRANSFERS = new Table("transfers", Transfer.class, OPTIONAL,
            new StringField("from_stop_id", REQUIRED),
            new StringField("to_stop_id", REQUIRED),
            new StringField("transfer_type", REQUIRED),
            new StringField("min_transfer_time", OPTIONAL)
    );

    public static final Table TRIPS = new Table("trips", Trip.class, REQUIRED,
        new StringField("trip_id",  REQUIRED),
        new StringField("route_id",  REQUIRED),
        new StringField("service_id",  REQUIRED),
        new StringField("trip_headsign",  OPTIONAL),
        new StringField("trip_short_name",  OPTIONAL),
        new ShortField("direction_id", OPTIONAL, 1),
        new StringField("block_id",  OPTIONAL),
        new StringField("shape_id",  OPTIONAL),
        new ShortField("wheelchair_accessible", OPTIONAL, 2),
        new ShortField("bikes_allowed", OPTIONAL, 2)
    );

    public Table (String name, Class<? extends Entity> entityClass, Requirement required, Field... fields) {
        this.name = name;
        this.entityClass = entityClass;
        this.required = required;
        this.fields = fields;
    }

    /**
     * Create an SQL table with all the fields specified by this table object,
     * plus an integer CSV line number field in the first position.
     */
    public void createSqlTable (Connection connection) {
        String fieldDeclarations = Arrays.stream(fields).map(Field::getSqlDeclaration).collect(Collectors.joining(", "));
        String dropSql = String.format("drop table if exists %s", name);
        // Adding the unlogged keyword gives about 12 percent speedup on loading, but is non-standard.
        String createSql = String.format("create table %s (csv_line integer, %s)", name, fieldDeclarations);
        try {
            Statement statement = connection.createStatement();
            statement.execute(dropSql);
            LOG.info(dropSql);
            statement.execute(createSql);
            LOG.info(createSql);
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    /**
     * Create an SQL table that will insert a value into all the fields specified by this table object,
     * plus an integer CSV line number field in the first position.
     */
    public String generateInsertSql () {
        String questionMarks = String.join(", ", Collections.nCopies(fields.length, "?"));
        String joinedFieldNames = Arrays.stream(fields).map(f -> f.name).collect(Collectors.joining(", "));
        return String.format("insert into %s (csv_line, %s) values (?, %s)", name, joinedFieldNames, questionMarks);
    }

    /**
     * @param name a column name from the header of a CSV file
     * @return the Field object from this table with the given name. If there is no such field defined, create
     * a new Field object for this name.
     */
    public Field getFieldForName(String name) {
        // Linear search, assuming a small number of fields per table.
        for (Field field : fields) if (field.name.equals(name)) return field;
        LOG.warn("Unrecognized header {}. Treating it as a proprietary string field.", name);
        return new StringField(name, UNKNOWN);
    }

    public String getKeyFieldName () {
        return fields[0].name;
    }

    public String getOrderFieldName () {
        String name = fields[1].name;
        if (name.contains("_sequence")) return name;
        else return null;
    }

    public String getIndexFields() {
        String orderFieldName = getOrderFieldName();
        if (orderFieldName == null) return getKeyFieldName();
        else return String.join(",", getKeyFieldName(), orderFieldName);
    }

    public Class<? extends Entity> getEntityClass() {
        return entityClass;
    }

    public boolean isRequired () {
        return required == REQUIRED;
    }

}
