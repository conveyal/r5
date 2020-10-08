package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.error.NewGTFSErrorType;
import com.conveyal.gtfs.storage.StorageException;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit tests to verify functionality of classes that load fields from GTFS tables.
 */
public class FieldTests {

    /**
     * Make sure our date field reader catches bad dates and accepts correct ones.
     */
    @Test
    public void dateFieldParseTest() {

        String[] badDates = {
                "20170733", // 33rd day of the month
                "20171402", // 14th months of the year
                "12252016", // month day year format
                "14921212",  // Year very far in the past
                "2015/03/11", // Slashes separating fields
                "2011-07-16", // Dashes separating fields
                "23000527", // Very distant future year
                "790722" // Two digit year
        };

        for (String badDate : badDates) {
            try {
                new DateField("date", Requirement.REQUIRED).validateAndConvert(badDate);
                assertThat("Parsing bad dates should throw an exception and never reach this point.", false);
            } catch (StorageException ex) {
                assertThat("Error type should be date-related.",
                        ex.errorType == NewGTFSErrorType.DATE_FORMAT || ex.errorType == NewGTFSErrorType.DATE_RANGE);
                assertThat("Error's bad value should be the input value (the bad date).", ex.badValue.equals(badDate));
            }
        }

        String[] goodDates = { "20160522", "20180717", "20221212", "20190505" };

        for (String goodDate : goodDates) {
            String convertedValue = new DateField("date", Requirement.REQUIRED).validateAndConvert(goodDate);
            assertThat("Returned value matches the well-formed date.", convertedValue.equals(goodDate));
        }

    }
}
