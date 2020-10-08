package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufDecoder;
import com.conveyal.data.geobuf.GeobufFeature;
import com.csvreader.CsvReader;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import junit.framework.TestCase;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test loading, extracting, etc.
 */
public class IntegrationTest extends TestCase {
    private CsvReader reader;
    private TLongObjectMap<GeobufFeature> features;
    
    /** test loading and extracting from a small state (DC) */
    @Test
    public void testAll () throws Exception {
        // extract the data
        // note that the LODES data is fake; we replaced every value with a unique number to ensure that we detect
        // swapped/incorrect column names; some columns are collinear in the original DC dataset (i.e. all zeros)
        // so they wouldn't show up in tests if we swapped them.
        // The python script in the resources directory alongside the data file takes a LODES CSV and replaces all the
        // values in it with unique numbers.
        File dir = Files.createTempDir();
        ZipInputStream zis = new ZipInputStream(getClass().getResourceAsStream("integrationTest.zip"));

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory())
                continue;

            File out = new File(dir, entry.getName());
            out.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            ByteStreams.copy(zis, fos);
            fos.close();
        }

        // load up the data
        CensusLoader.main(dir.getAbsolutePath());

        // do an extract (this crosses a tile boundary)
        CensusExtractor.main(new File(dir, "tiles").getAbsolutePath(), "38.9872", "-77.0378", "38.9218", "-77.1086", new File(dir, "extract.pbf").getAbsolutePath());

        // load the extract
        FileInputStream fis = new FileInputStream(new File(dir, "extract.pbf"));
        GeobufDecoder decoder = new GeobufDecoder(fis);

        assertTrue(decoder.hasNext());

        Envelope envelope = new Envelope(-77.1086, -77.0378, 38.9218, 38.9872);

        features = new TLongObjectHashMap<>();

        while (decoder.hasNext()) {
            GeobufFeature feat = decoder.next();
            // TODO the extractor has a true geometric overlap check, not just an envelope check
            // so this check could be made more specific
            assertTrue(feat.geometry.getEnvelopeInternal().intersects(envelope));

            features.put(feat.numericId, feat);
            assertNotSame(0, feat.numericId);
        }

        // > 1 to ensure all numeric IDs are not the same
        assertTrue(features.size() > 1);
        // random census block in NW DC
        assertTrue(features.containsKey(110010014023009L));

        // read the workplace area characteristics csv
        InputStream csv = new GZIPInputStream(new FileInputStream(new File(new File(dir, "jobs"), "dc_wac_S000_JT00_2013.csv.gz")));
        reader = new CsvReader(new InputStreamReader(csv));
        reader.readHeaders();

        // make sure we found a jobs entry
        boolean foundJobsEntry = false;

        String[] line;
        while (reader.readRecord()) {
            // make sure everything matches, and that we got the column name mappings correct
            foundJobsEntry = foundJobsEntry || check("Jobs at firms aged 0-1 years", "CFA01");
            check("Jobs at firms aged 2-3 years", "CFA02");
            check("Jobs at firms aged 4-5 years", "CFA03");
            check("Jobs at firms aged 6-10 years", "CFA04");
            check("Jobs at firms aged 11 or more years", "CFA05");

            check("Jobs at firms with 0-19 employees", "CFS01");
            check("Jobs at firms with 20-49 employees", "CFS02");
            check("Jobs at firms with 50-249 employees", "CFS03");
            check("Jobs at firms with 250-499 employees", "CFS04");
            check("Jobs at firms with 500 or more employees", "CFS05");

            check("Jobs employing Hispanic or Latino workers", "CT02");
            check("Jobs employing not Hispanic or Latino workers", "CT01");

            check("Jobs employing females", "CS02");
            check("Jobs employing males", "CS01");

            check("Jobs employing workers age 29 or younger", "CA01");
            check("Jobs employing workers age 30 to 54", "CA02");
            check("Jobs employing workers age 55 or older", "CA03");

            check("Jobs employing workers with less than high school education", "CD01");
            check("Jobs employing workers with high school education, no college", "CD02");
            check("Jobs employing workers with some college education or Associate degree", "CD03");
            check("Jobs employing workers with Bachelor's degree or higher", "CD04");

            check("Jobs employing workers with race American Indian or Alaska Native alone", "CR03");
            check("Jobs employing workers with race Asian alone", "CR04");
            check("Jobs employing workers with race Black or African American alone", "CR02");
            check("Jobs employing workers with race Native Hawaiian or Other Pacific Islander alone", "CR05");
            check("Jobs employing workers with race White alone", "CR01");
            check("Jobs employing workers with two or more racial groups", "CR07");

            check("Jobs in accommodation and food services", "CNS18");
            check("Jobs in administration, support, and waste management", "CNS14");
            check("Jobs in agriculture, forestry, fishing and hunting", "CNS01");
            check("Jobs in arts, entertainment and recreation", "CNS17");
            check("Jobs in construction", "CNS04");
            check("Jobs in educational services", "CNS15");
            check("Jobs in finance and insurance", "CNS10");
            check("Jobs in healthcare and social assistance", "CNS16");
            check("Jobs in information", "CNS09");
            check("Jobs in management", "CNS13");
            check("Jobs in manufacturing", "CNS05");
            check("Jobs in mining, quarrying, and oil and gas extraction", "CNS02");
            check("Jobs in other services, except public administration", "CNS19");
            check("Jobs in professional, scientific and technical services", "CNS12");
            check("Jobs in public administration", "CNS20");
            check("Jobs in real estate", "CNS11");
            check("Jobs in retail trade", "CNS07");
            check("Jobs in transportation and warehousing", "CNS08");
            check("Jobs in utilities", "CNS03");
            check("Jobs in wholesale trade", "CNS06");
            
            check("Jobs total", "C000");
            
            check("Jobs with earnings $1250 per month or less", "CE01");
            check("Jobs with earnings $1251 - $3333 per month", "CE02");
            check("Jobs with earnings greater than $3333 per month", "CE03");
        }
        csv.close();

        assertTrue(foundJobsEntry);

        // read the rac csv
        csv = new GZIPInputStream(new FileInputStream(new File(new File(dir, "workforce"), "dc_rac_S000_JT00_2013.csv.gz")));
        reader = new CsvReader(new InputStreamReader(csv));

        reader.readHeaders();

        boolean foundWorkforceEntry = false;

        while (reader.readRecord()) {
            foundWorkforceEntry = foundWorkforceEntry || check("Workers age 29 or younger", "CA01");
            check("Workers age 30 to 54", "CA02");
            check("Workers age 55 or older", "CA03");

            check("Workers in accommodation and food services", "CNS18");
            check("Workers in administration, support, and waste management", "CNS14");
            check("Workers in agriculture, forestry, fishing and hunting", "CNS01");
            check("Workers in arts, entertainment and recreation", "CNS17");
            check("Workers in construction", "CNS04");
            check("Workers in educational services", "CNS15");
            check("Workers in finance and insurance", "CNS10");
            check("Workers in healthcare and social assistance", "CNS16");
            check("Workers in information", "CNS09");
            check("Workers in management", "CNS13");
            check("Workers in manufacturing", "CNS05");
            check("Workers in mining, quarrying, and oil and gas extraction", "CNS02");
            check("Workers in other services, except public administration", "CNS19");
            check("Workers in professional, scientific and technical services", "CNS12");
            check("Workers in public administration", "CNS20");
            check("Workers in real estate", "CNS11");
            check("Workers in retail trade", "CNS07");
            check("Workers in transportation and warehousing", "CNS08");
            check("Workers in utilities", "CNS03");
            check("Workers in wholesale trade", "CNS06");

            check("Workers total", "C000");

            check("Workers with earnings $1250 per month or less", "CE01");
            check("Workers with earnings $1251 - $3333 per month", "CE02");
            check("Workers with earnings greater than $3333 per month", "CE03");

            check("Workers with less than high school education", "CD01");
            check("Workers with high school education, no college", "CD02");
            check("Workers with some college education or Associate degree", "CD03");
            check("Workers with Bachelor's degree or higher", "CD04");

            check("Workers with race American Indian or Alaska Native alone", "CR03");
            check("Workers with race Asian alone", "CR04");
            check("Workers with race Black or African American alone", "CR02");
            check("Workers with race Native Hawaiian or Other Pacific Islander alone", "CR05");
            check("Workers with race White alone", "CR01");
            check("Workers with two or more racial groups", "CR07");

            check("Workers, Hispanic or Latino", "CT02");
            check("Workers, not Hispanic or Latino", "CT01");

            check("Workers, female", "CS02");
            check("Workers, male", "CS01");
        }
        csv.close();

        assertTrue(foundWorkforceEntry);
        dir.delete();
    }
    
    private boolean check (String colName, String colCode) throws Exception {
        long fid;

        // TODO cache?
        Set<String> headers = new HashSet<>(Arrays.asList(reader.getHeaders()));

        if (headers.contains("w_geocode"))
            fid = Long.parseLong(reader.get("w_geocode"));
        else
            fid = Long.parseLong(reader.get("h_geocode"));

        // cast to primitive long so as not to confuse Java's type inference
        if (features.containsKey(fid)) {
            assertEquals((long) Long.parseLong(reader.get(colCode)), (long) features.get(fid).properties.get(colName));
            return true;
        }
        else return false;
    }
}
