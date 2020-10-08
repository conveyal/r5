package com.conveyal.gtfs;

import com.csvreader.CsvReader;
import org.apache.commons.io.input.BOMInputStream;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.conveyal.gtfs.TestUtils.getResourceFileName;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test suite for the GTFSFeed class.
 */
public class GTFSFeedTest {

    private static final Logger LOG = LoggerFactory.getLogger(GTFSFeedTest.class);

    private static class FileTestCase {
        public String filename;
        public DataExpectation[] expectedColumnData;

        public FileTestCase(String filename, DataExpectation[] expectedColumnData) {
            this.filename = filename;
            this.expectedColumnData = expectedColumnData;
        }
    }

    private static class DataExpectation {
        public String columnName;
        public String expectedValue;

        public DataExpectation(String columnName, String expectedValue) {
            this.columnName = columnName;
            this.expectedValue = expectedValue;
        }
    }

    /**
     * Make sure a roundtrip of loading a GTFS zip file and then writing another zip file can be performed.
     */
    @Test
    public void canDoRoundtripLoadAndWriteToZipFile() throws IOException {
        // create a temp file for this test
        File outZip = File.createTempFile("fake-agency-output", ".zip");

        // delete file to make sure we can assert that this program created the file
        outZip.delete();

        GTFSFeed feed = GTFSFeed.fromFile(getResourceFileName("fake-agency.zip"));
        feed.toFile(outZip.getAbsolutePath());
        feed.close();
        assertThat(outZip.exists(), is(true));

        // assert that rows of data were written to files within the zipfile
        ZipFile zip = new ZipFile(outZip);

        FileTestCase[] fileTestCases = {
            // agency.txt
            new FileTestCase(
                "agency.txt",
                new DataExpectation[]{
                    new DataExpectation("agency_id", "1"),
                    new DataExpectation("agency_name", "Fake Transit")
                }
            ),
            new FileTestCase(
                "calendar.txt",
                new DataExpectation[]{
                    new DataExpectation("service_id", "04100312-8fe1-46a5-a9f2-556f39478f57"),
                    new DataExpectation("start_date", "20170915"),
                    new DataExpectation("end_date", "20170917")
                }
            ),
            new FileTestCase(
                "routes.txt",
                new DataExpectation[]{
                    new DataExpectation("agency_id", "1"),
                    new DataExpectation("route_id", "1"),
                    new DataExpectation("route_long_name", "Route 1")
                }
            ),
            new FileTestCase(
                "shapes.txt",
                new DataExpectation[]{
                    new DataExpectation("shape_id", "5820f377-f947-4728-ac29-ac0102cbc34e"),
                    new DataExpectation("shape_pt_lat", "37.0612132"),
                    new DataExpectation("shape_pt_lon", "-122.0074332")
                }
            ),
            new FileTestCase(
                "stop_times.txt",
                new DataExpectation[]{
                    new DataExpectation("trip_id", "a30277f8-e50a-4a85-9141-b1e0da9d429d"),
                    new DataExpectation("departure_time", "07:00:00"),
                    new DataExpectation("stop_id", "4u6g")
                }
            ),
            new FileTestCase(
                "trips.txt",
                new DataExpectation[]{
                    new DataExpectation("route_id", "1"),
                    new DataExpectation("trip_id", "a30277f8-e50a-4a85-9141-b1e0da9d429d"),
                    new DataExpectation("service_id", "04100312-8fe1-46a5-a9f2-556f39478f57")
                }
            )
        };

        // look through all written files in the zipfile
        for (FileTestCase fileTestCase: fileTestCases) {
            ZipEntry entry = zip.getEntry(fileTestCase.filename);

            // make sure the file exists within the zipfile
            assertThat(entry, notNullValue());

            // create csv reader for file
            InputStream zis = zip.getInputStream(entry);
            InputStream bis = new BOMInputStream(zis);
            CsvReader reader = new CsvReader(bis, ',', Charset.forName("UTF8"));

            // make sure the file has headers
            boolean hasHeaders = reader.readHeaders();
            assertThat(hasHeaders, is(true));

            // make sure that the a record matching the expected row exists in this table
            boolean recordFound = false;
            while (reader.readRecord() && !recordFound) {
                boolean allExpectationsMetForThisRecord = true;
                for (DataExpectation dataExpectation : fileTestCase.expectedColumnData) {
                    if(!reader.get(dataExpectation.columnName).equals(dataExpectation.expectedValue)) {
                        allExpectationsMetForThisRecord = false;
                        break;
                    }
                }
                if (allExpectationsMetForThisRecord) {
                    recordFound = true;
                }
            }
            assertThat(
                "Data Expectation record not found in " + fileTestCase.filename,
                recordFound,
                is(true)
            );
        }
    }
}