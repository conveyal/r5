package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.UnsupportedFlexError;
import com.conveyal.gtfs.geom.GeoJsonStreamer;
import com.conveyal.gtfs.geom.GeoJsonStreamer.FeatureProblem;
import com.conveyal.gtfs.geom.GeoJsonStreamer.IdSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Stream GTFS Flex locations (GeoJSON polygonal zones) into a GTFSFeed's (typically disk-backed)
/// Map keyed on ID. Though this loads a GTFS table, it does not use Entity.Loader because that
/// assumes files are CSV and their names end in .txt. Rather than buffer all the objects in
/// memory, we store them one by one as they are decoded.
///
/// Background for this mechanism: GeoTools has some fairly heavy abstractions and poses
/// serialization difficulties. It is generic across coordinate storage schemes, spatial
/// reference systems, and precision models, and will store object graphs capturing this
/// information for every geometry instance. We sidestep that by using our own lightweight
/// geometry classes, which requires replicating some GeoJSON loading capability. We take that
/// opportunity to parse in an entirely streaming manner (see [GeoJsonStreamer] for details).
///
/// Features that do not define usable zones (no id, or geometry that is missing, non-polygonal,
/// malformed, or invalid) are skipped individually, and one summary error per file reports how
/// many were ignored, tallied by kind. Trips referencing such skipped zones
/// are caught separately by referential integrity checks.
public abstract class FlexLocationStreamer {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static void loadLocationsJson (ZipFile zip, GTFSFeed feed) throws Exception {
        ZipEntry entry = zip.getEntry("locations.geojson");
        if (entry == null) {
            LOG.info("GTFS feed does not have locations.geojson specifying flex zones.");
            return;
        }
        loadLocationsJson(zip.getInputStream(entry), feed);
    }

    /// Load the contents of a locations.geojson file from the given stream into the given feed.
    /// Tests call this directly with literal GeoJSON, bypassing the layer that loads from ZIP.
    /// The top level must be a FeatureCollection and every feature must have a string ID.
    public static void loadLocationsJson (InputStream inStream, GTFSFeed feed) {
        GeoJsonStreamer streamer = new GeoJsonStreamer(inStream, IdSource.FEATURE_ID, "stop_name");
        streamer.stream((id, name, geometry) -> {
            if (!geometry.validate()) {
                // A structurally parseable polygon is still invalid (zero area, self-crossing rings).
                streamer.tallyProblem(FeatureProblem.INVALID_GEOMETRY);
                return;
            }
            // Oddly, the name of a location (which is a zone and never a stop) is called stop_name.
            feed.locations.put(id, new FlexLocation(id, name, null, geometry));
        });
        if (streamer.numericFeatureIdCount() > 0) {
            feed.errors.add(new UnsupportedFlexError("locations.geojson", -1, "id", String.format(
                "%d feature id(s) are numbers rather than the strings GTFS requires; they were interpreted as strings.",
                streamer.numericFeatureIdCount())));
        }
        if (streamer.skippedFeatureCount() > 0) {
            feed.errors.add(new UnsupportedFlexError("locations.geojson", -1, "features", String.format(
                "Ignored %d feature(s) that do not define usable flex zones: %s.",
                streamer.skippedFeatureCount(), streamer.describeProblems())));
        }
    }

}
