package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.GTFSError;
import com.conveyal.gtfs.error.ReferentialIntegrityError;
import com.conveyal.gtfs.error.UnsupportedFlexError;
import com.conveyal.gtfs.validator.PostLoadValidator;
import com.conveyal.gtfs.validator.model.Priority;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// This set of tests loads small GTFS-Flex feeds and builds TransportNetworks from them.
/// The zipped GTFS feeds are built from directories of txt files. For each feed, we check whether
/// (a) unsupported flex features or incorrect data are detected and recorded on the GTFSFeed
/// during load or post-load validation, and (b) building a TransportNetwork tolerates unsupported
/// flex trips, skipping over them and still importing supported flex trips and scheduled trips.
///
/// Feeds that cause HIGH-priority errors are only loaded and no network is built. The Conveyal
/// UI prevents end users from using such feeds in a network.
///
/// Most temporary files produced by these tests (GTFS, OSM PBF and MapDB) are written with known
/// names into a per-test @TempDir that JUnit deletes. The one exception is the read-only GTFS
/// MapDB that TransportNetwork.fromFiles creates internally via createTempFile.
public class FlexNetworkBuildTest {

    /// Classpath location of an OSM fixture provided by other tests
    private static final String COLUMBUS_OSM_RESOURCE = "/com/conveyal/r5/analyst/scenario/columbus.osm.pbf";

    /// Temporary directory created and deleted per test method (JUnit default PER_METHOD lifecycle).
    @TempDir
    private Path tempDir;

    /// The GTFS zip created by loadFixture, to be loaded into a GTFSFeed.
    private Path gtfsZip;

    /// The writable feed loaded by loadFixture, kept open for error assertions and closed afterwards.
    private GTFSFeed feed;

    /// Close any files left open before JUnit deletes the @TempDir contents.
    @AfterEach
    void closeFeed () {
        if (feed != null) {
            feed.close();
        }
    }

    // --- Tests that load GTFS and proceed to build a network (worst error if any is MEDIUM) ---

    /// Check that the valid fixture has four ingestible flex trips demonstrating each supported
    /// reference combination (location->location, location->location_group, location_group->location,
    /// location_group->location_group), plus a scheduled non-flex trip mixed into the same feed.
    @Test
    void supportedFlexTripVariantsAllBuild () throws Exception {
        loadFixture("gtfs/flex/valid");
        assertEquals(0, countErrors(feed, UnsupportedFlexError.class), "No UnsupportedFlexError expected.");
        assertEquals(0, countErrors(feed, ReferentialIntegrityError.class), "No bad references expected.");
        TransportNetwork network = buildNetwork();
        assertEquals(4, onDemandCount(network), "Each supported flex trip variant should yield one service.");
        assertEquals(1, network.transitLayer.tripPatterns.size(),
                "A scheduled trip in the same feed should also load, alongside the flex services.");
        for (OnDemand od : network.transitLayer.onDemandIndex.allServices()) {
            assertEquals(8 * 3600, od.fromWindowStart, "Pickup window start should be loaded.");
            assertEquals(12 * 3600, od.fromWindowEnd, "Pickup window end should be loaded.");
            assertEquals(12 * 3600, od.toWindowEnd,
                    "The drop-off window end should be loaded from the trip's second stop_time.");
        }
    }

    /// Check that a flex trip declaring no pickup/drop-off time window is flagged at feed load
    /// (the spec requires the windows) but still builds as an always-available service, with
    /// its missing bounds treated as unlimited. This is the same internal representation that
    /// PickupDelay-derived services (which have no windows) will use once merged into OnDemand.
    @Test
    void flexTripWithoutTimeWindowIsAlwaysAvailable () throws Exception {
        loadFixture("gtfs/flex/nowindow");
        assertTrue(hasFlexErrorContaining(feed, "window"),
                "Missing windows should be flagged as an error at feed load time.");
        TransportNetwork network = buildNetwork();
        assertEquals(4, onDemandCount(network),
                "The windowless flex trip should build alongside its three windowed siblings.");
        assertEquals(1, network.transitLayer.tripPatterns.size(),
                "The scheduled non-flex trip should also load.");
        OnDemand windowless = network.transitLayer.onDemandIndex.allServices().stream()
                .filter(od -> od.id.equals("T1")).findFirst().orElseThrow();
        assertEquals(0, windowless.fromWindowStart, "A missing window start should become 0.");
        assertEquals(Integer.MAX_VALUE, windowless.fromWindowEnd,
                "A missing pickup window end should become unlimited.");
        assertEquals(Integer.MAX_VALUE, windowless.toWindowEnd,
                "A missing drop-off window end should become unlimited.");
    }

    /// Check that the threestops fixture has one rejected unsupported flex trip (with three
    /// stop_times) and one supported scheduled trip.
    @Test
    void unsupportedFlexTripIsSkippedWhileValidOneBuilds () throws Exception {
        loadFixture("gtfs/flex/threestops");
        assertTrue(hasFlexErrorContaining(feed, "stop_times"),
                "The trip with three stop_times should be flagged as unsupported.");
        TransportNetwork network = buildNetwork();
        assertEquals(1, onDemandCount(network),
                "The valid two-stop flex trip should build while the three-stop trip is skipped.");
        assertEquals(1, network.transitLayer.tripPatterns.size(),
                "The scheduled non-flex trip should also load.");
    }

    /// Check that a flex trip referencing pointlike stops is flagged and skipped, while a
    /// supported scheduled trip in the same feed is loaded.
    @Test
    void pointlikeStopFlexTripIsFlaggedAndSkipped () throws Exception {
        loadFixture("gtfs/flex/pointstop");
        assertTrue(hasFlexErrorContaining(feed, "pointlike"),
                "Referencing pointlike stops in a flex trip should be flagged as unsupported.");
        TransportNetwork network = buildNetwork();
        assertEquals(0, onDemandCount(network), "A flex trip referencing pointlike stops should be skipped.");
        assertEquals(1, network.transitLayer.tripPatterns.size(),
                "The scheduled non-flex trip should still load even though the flex trip was skipped.");
    }

    // --- Tests that only load GTFS but do not build networks due to HIGH severity errors ---

    /// The presence of any geometry of an unsupported type should cause the GeoJSON file to be
    /// rejected. Trips referencing those geometries are then expected to have broken references.
    /// This is currently triggered by a multipolygon, but if and when multipolygons are supported
    /// (if found in GTFS feeds in the wild) this test will shift to another type like linestring.
    @Test
    void unsupportedGeometryDropsLocations () throws Exception {
        loadFixture("gtfs/flex/multipolygon");
        assertTrue(feed.locations.isEmpty(),
                "Any unsupported geometry should make the whole locations.geojson unusable.");
        assertTrue(hasFlexErrorContaining(feed, "MultiPolygon"),
                "The unsupported geometry type should be recorded.");
        assertTrue(hasHighPriorityError(feed, ReferentialIntegrityError.class),
                "Trips referencing the now-absent locations now contain bad references.");
    }

    @Test
    void badReferenceDetected () throws Exception {
        loadFixture("gtfs/flex/badref");
        assertTrue(hasHighPriorityError(feed, ReferentialIntegrityError.class),
                "A stop_time referencing a nonexistent location should yield a referential integrity error.");
    }

    // --- Convenience methods reused across tests ---

    /// Shared load steps performed at the start of every test: zip the named fixture folder into
    /// the per-test temp dir, load that feed and run post-load validation.
    /// The resulting feed is retained for subsequent test-specific assertions.
    private void loadFixture (String fixtureDir) throws Exception {
        gtfsZip = tempDir.resolve("feed.zip");
        zipFixture(fixtureDir, gtfsZip);
        feed = GTFSFeed.newWritableFile(tempDir.resolve("feed.db").toFile());
        feed.loadFromFile(new ZipFile(gtfsZip.toFile()), null);
        new PostLoadValidator(feed).validate();
    }

    /// Build a TransportNetwork from a fixture previously loaded by loadFixture.
    /// The OSM input is copied to the per-test temp dir so its derived MapDB is also temporary.
    /// STRAY TEMP FILES: TransportNetwork.fromFiles loads each GTFS zip into a read-only MapDB
    /// created with File.createTempFile, so every build leaves db.p files in the system temp dir.
    /// We may want to revise GTFSFeed.readOnlyTempFileFromGtfs to ensure timely removal.
    private TransportNetwork buildNetwork () throws Exception {
        String osmPath = copyColumbusOsm();
        return TransportNetwork.fromFiles(osmPath, List.of(gtfsZip.toString()));
    }

    /// @return the number of on-demand services in the network (zero if no OnDemandIndex present).
    private static int onDemandCount (TransportNetwork network) {
        return network.transitLayer.onDemandIndex == null ? 0 : network.transitLayer.onDemandIndex.size();
    }

    /// Zip the loose files of a test resource folder into the given target ZIP file.
    /// Used to create standard zipped GTFS files from version-controlled TXT test fixtures.
    private static void zipFixture (String fixtureDir, Path zipTarget) throws IOException, URISyntaxException {
        File sourceDir = resourceDir(fixtureDir);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipTarget.toFile()))) {
            for (File file : sourceDir.listFiles()) {
                if (!file.isFile()) continue;
                zip.putNextEntry(new ZipEntry(file.getName()));
                Files.copy(file.toPath(), zip);
                zip.closeEntry();
            }
        }
    }

    /// Resolve a test resource directory via the classpath (independent of the working directory,
    /// and consistent with how the OSM fixture is loaded). Throws exceptions to signal problems.
    private static File resourceDir (String resourcePath) throws URISyntaxException {
        URL url = FlexNetworkBuildTest.class.getResource("/" + resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("Test resource directory not found on classpath: " + resourcePath);
        }
        File dir = new File(url.toURI());
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Test resource is not a directory: " + resourcePath);
        }
        return dir;
    }

    /// Copy the Columbus OSM PBF fixture into the test temp dir so its derived .mapdb side-files
    /// are also written there (and cleaned up with the temp dir) rather than into the source tree.
    private String copyColumbusOsm () throws IOException {
        Path pbf = tempDir.resolve("columbus.osm.pbf");
        try (InputStream is = FlexNetworkBuildTest.class.getResourceAsStream(COLUMBUS_OSM_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("OSM fixture not found on classpath: " + COLUMBUS_OSM_RESOURCE);
            }
            Files.copy(is, pbf);
        }
        return pbf.toString();
    }

    private static long countErrors (GTFSFeed feed, Class<? extends GTFSError> type) {
        return feed.errors.stream().filter(type::isInstance).count();
    }

    private static boolean hasHighPriorityError (GTFSFeed feed, Class<? extends GTFSError> type) {
        return feed.errors.stream()
                .filter(type::isInstance)
                .anyMatch(e -> e.getPriority() == Priority.HIGH);
    }

    private static boolean hasFlexErrorContaining (GTFSFeed feed, String messageSubstring) {
        return feed.errors.stream()
                .filter(UnsupportedFlexError.class::isInstance)
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains(messageSubstring));
    }

}
