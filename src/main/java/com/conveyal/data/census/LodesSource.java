package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufFeature;
import com.csvreader.CsvReader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Data source for LODES data.
 */
public class LodesSource {
    private File input;
    private LodesType type;

    public LodesSource(File input, LodesType type) {
        this.input = input;
        this.type = type;
    }

    public void load(ShapeDataStore store) throws Exception {
        InputStream csv = new GZIPInputStream(new BufferedInputStream(new FileInputStream(input)));
        CsvReader reader = new CsvReader(new InputStreamReader(csv));

        // rename the columns to something useful
        //http://lehd.ces.census.gov/data/lodes/LODES7/LODESTechDoc7.1.pdf#page=7&zoom=auto,-266,580
        Map<String, String> colNames = new HashMap<>();
        colNames.put("C000", "total");

        colNames.put("CA01", "age 29 or younger");
        colNames.put("CA02", "age 30 to 54");
        colNames.put("CA03", "age 55 or older");

        colNames.put("CE01", "with earnings $1250 per month or less");
        colNames.put("CE02", "with earnings $1251 - $3333 per month");
        colNames.put("CE03", "with earnings greater than $3333 per month");

        colNames.put("CNS01", "in agriculture, forestry, fishing and hunting");
        colNames.put("CNS02", "in mining, quarrying, and oil and gas extraction");
        colNames.put("CNS03", "in utilities");
        colNames.put("CNS04", "in construction");
        colNames.put("CNS05", "in manufacturing");
        colNames.put("CNS06", "in wholesale trade");
        colNames.put("CNS07", "in retail trade");
        colNames.put("CNS08", "in transportation and warehousing");
        colNames.put("CNS09", "in information");
        colNames.put("CNS10", "in finance and insurance");
        colNames.put("CNS11", "in real estate");
        colNames.put("CNS12", "in professional, scientific and technical services");
        colNames.put("CNS13", "in management");
        colNames.put("CNS14", "in administration, support, and waste management");
        colNames.put("CNS15", "in educational services");
        colNames.put("CNS16", "in healthcare and social assistance");
        colNames.put("CNS17", "in arts, entertainment and recreation");
        colNames.put("CNS18", "in accommodation and food services");
        colNames.put("CNS19", "in other services, except public administration");
        colNames.put("CNS20", "in public administration");

        colNames.put("CR01", "with race White alone");
        colNames.put("CR02", "with race Black or African American alone");
        colNames.put("CR03", "with race American Indian or Alaska Native alone");
        colNames.put("CR04", "with race Asian alone");
        colNames.put("CR05", "with race Native Hawaiian or Other Pacific Islander alone");
        colNames.put("CR07", "with two or more racial groups");

        colNames.put("CT01", "not Hispanic or Latino");
        colNames.put("CT02", "Hispanic or Latino");

        colNames.put("CD01", "with less than high school education");
        colNames.put("CD02", "with high school education, no college");
        colNames.put("CD03", "with some college education or Associate degree");
        colNames.put("CD04", "with Bachelor's degree or higher");
        colNames.put("CS01", "male");
        colNames.put("CS02", "female");

        // only in workplace characteristics
        colNames.put("CFA01", "at firms aged 0-1 years");
        colNames.put("CFA02", "at firms aged 2-3 years");
        colNames.put("CFA03", "at firms aged 4-5 years");
        colNames.put("CFA04", "at firms aged 6-10 years");
        colNames.put("CFA05", "at firms aged 11 or more years");

        colNames.put("CFS01", "at firms with 0-19 employees");
        colNames.put("CFS02", "at firms with 20-49 employees");
        colNames.put("CFS03", "at firms with 50-249 employees");
        colNames.put("CFS04", "at firms with 250-499 employees");
        colNames.put("CFS05", "at firms with 500 or more employees");
        colNames.put("createdate", "Data creation date");

        reader.readHeaders();
        String[] headers = reader.getHeaders();

        // read the file
        while (reader.readRecord()) {
            long id = Long.parseLong(reader.get(type == LodesType.WORKPLACE ? "w_geocode" : "h_geocode"));
            GeobufFeature feat = store.get(id);

            String[] line = reader.getValues();
            for (int i = 0; i < line.length; i++) {
                String col = headers[i];

                if (!colNames.containsKey(col))
                    continue;

                String colName;

                if (type == LodesType.WORKPLACE) {
                    if (col.startsWith("CR") || col.startsWith("CD") || col.startsWith("CA"))
                        colName = "Jobs employing workers " + colNames.get(col);
                    else if (col.startsWith("CS"))
                        colName = "Jobs employing " + colNames.get(col) + "s";
                    else if (col.startsWith("CT"))
                        colName = "Jobs employing " + colNames.get(col) + " workers";
                    else
                        colName = "Jobs " + colNames.get(col);
                }
                else if (type == LodesType.RESIDENCE) {
                    if (col.startsWith("CT") || col.startsWith("CS"))
                        colName = "Workers, " + colNames.get(col);
                    else
                        colName = "Workers " + colNames.get(col);
                }
                else {
                    throw new IllegalArgumentException("Invalid LODES type");
                }

                feat.properties.put(colName, Integer.parseInt(line[i]));
            }

            store.put(feat);
        }

        reader.close();
    }

    /** supported lodes types are workplace area characteristics and residence area characteristics */
    public static enum LodesType {
        WORKPLACE, RESIDENCE
    }
}
