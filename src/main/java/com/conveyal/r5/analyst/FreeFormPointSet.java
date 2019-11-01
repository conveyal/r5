package com.conveyal.r5.analyst;

import com.conveyal.r5.util.InputStreamProvider;
import com.csvreader.CsvReader;
import com.vividsolutions.jts.geom.Polygon;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.apache.commons.io.input.BOMInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * PointSets serve as groups of destinations or origins. They were a key resource in the legacy web analyst
 * (aka OTP Analyst aka TransportAnalyst). For the first couple years of the new Conveyal Analysis, only
 * WebMercatorGridPointSets were supported. This class re-enables non-gridded pointsets, re-using/modifying some of the
 * legacy code, which was removed in R5 PR #338.
 */
public class FreeFormPointSet extends PointSet implements Serializable {

    private static final long serialVersionUID = -8962916330731463238L;

    private static final Logger LOG = LoggerFactory.getLogger(FreeFormPointSet.class);

    /** The file extension we use when persisting freeform pointsets to files. */
    public static final String fileExtension = ".freeform";

    /** A server-unique identifier for this FreeFormPointSet */
    public String id;

    /** A short description of this FreeFormPointSet for use in a legend or menu */
    public String label;

    /** A detailed textual description of this FreeFormPointSet */
    public String description;

    public Map<String, double[]> properties = new HashMap<>();

    public int capacity = 0; // The total number of features this FreeFormPointSet holds.

    /**
     * Map from string IDs to their array indices. This is a view into FreeFormPointSet.ids, namely its reverse mapping.
     */
    private transient TObjectIntMap<String> idIndexMap;

    // The characteristics of the features in this FreeFormPointSet. This is a column store.

    /** A unique identifier for each feature. */
    public String[] ids;

    /** The latitude of each feature (or its centroid if it's not a point). */
    protected double[] lats;

    /** The longitude of each feature (or its centroid if it's not a point). */
    protected double[] lons;

    /** The polygon for each feature (which is reduced to a centroid point for routing purposes). */
    protected Polygon[] polygons; // TODO what do we do when there are no polygons?

    /**
     * Create a FreeFormPointset from a CSV file, which must have latitude and longitude columns with the values of
     * latField and lonField in the header row. If idField is supplied, its column will be used to supply id values
     * for the points; if not, row numbers will be used as the ids.
     * Comment lines are allowed in these input files, and begin with a #. TODO verify that this is in fact true.
     */
    public static FreeFormPointSet fromCsv(InputStreamProvider csvInputStreamProvider,
                                           String latField,
                                           String lonField,
                                           String idField,
                                           Collection<String> ignoreFields) throws IOException {

        /* First, scan through the file to count lines and check for errors. */
        InputStream csvInputStream = new BOMInputStream(new BufferedInputStream(csvInputStreamProvider.getInputStream()));
        CsvReader reader = new CsvReader(csvInputStream, ',', StandardCharsets.UTF_8);
        reader.readHeaders();
        int nCols = reader.getHeaderCount();
        while (reader.readRecord()) {
            if (reader.getColumnCount() != nCols) {
                LOG.error("CSV record {} has the wrong number of fields.", reader.getCurrentRecord());
                return null;
            }
        }
        // getCurrentRecord is zero-based and does not include headers or blank lines
        int nRecs = (int) reader.getCurrentRecord() + 1;
        // This also closes the input stream.
        reader.close();

        /* If we reached here, the file is entirely readable. Start over. */
        csvInputStream = new BOMInputStream(new BufferedInputStream(csvInputStreamProvider.getInputStream()));
        reader = new CsvReader(csvInputStream, ',', StandardCharsets.UTF_8);
        FreeFormPointSet ret = new FreeFormPointSet(nRecs);
        ret.description = "description";
        ret.label = "label";
        reader.readHeaders();
        if (reader.getHeaderCount() != nCols) {
            LOG.error("Number of headers changed.");
            return null;
        }
        int idCol = -1;
        int latCol = -1;
        int lonCol = -1;

        // This array maps columns in the CSV to property names. Some columns we don't want to load will remain null.
        // An extra column is added (called "count") to allow counting reachable points - it is filled with 1s.
        String[] propertyNames = new String[nCols + 1];

        // An array of property magnitudes corresponding to each numeric column in the CSV input.
        // Some of these will remain null (specifically, the lat and lon columns which do not contain magnitudes).
        double[][] properties = new double[nCols + 1][ret.capacity];
        for (int c = 0; c < nCols; c++) {
            String header = reader.getHeader(c);
            if (ignoreFields != null && ignoreFields.contains(header)) {
                continue;
            }
            if (header.equals(latField)) {
                latCol = c;
            } else if (header.equalsIgnoreCase(lonField)) {
                lonCol = c;
            } else if (header.equalsIgnoreCase(idField)) {
                idCol = c;
            } else {
                propertyNames[c] = header;
                properties[c] = new double[ret.capacity];
            }
        }
        if (latCol < 0 || lonCol < 0) {
            LOG.error("CSV file did not contain the specified latitude or longitude column.");
            throw new IOException();
        }

        ret.ids = new String[nRecs];
        ret.lats = new double[nRecs];
        ret.lons = new double[nRecs];

        // A virtual column of all 1s, to allow counting reachable points rather than summing their magnitudes.
        propertyNames[nCols] = "Count";
        properties[nCols] = new double[ret.capacity];

        while (reader.readRecord()) {
            int rec = (int) reader.getCurrentRecord();
            properties[nCols][rec] = 1; // The count column has value 1 for every record.
            for (int c = 0; c < nCols; c++) {
                if(c == latCol || c == lonCol || c == idCol){
                    continue;
                }
                double[] prop = properties[c];
                double mag = Double.parseDouble(reader.get(c));
                prop[rec] = mag;
            }
            // If ID column was found, use it; otherwise, use record number
            ret.ids[rec] = idCol < 0 ? String.valueOf(rec) : reader.get(idCol);
            ret.lats[rec] = Double.parseDouble(reader.get(latCol));
            ret.lons[rec] = Double.parseDouble(reader.get(lonCol));
        }
        ret.capacity = nRecs;
        for (int i = 0; i < propertyNames.length; i++) {
            if (propertyNames[i] != null) {
                ret.properties.put(propertyNames[i], properties[i]);
            }
        }
        return ret;
    }

    /**
     * Create a FreeFormPointSet manually by defining capacity and calling
     * addFeature(geom, data) repeatedly.
     *
     * @param capacity
     *            expected number of features to be added to this FreeFormPointSet.
     */
    public FreeFormPointSet(int capacity) {
        this.capacity = capacity;
        ids = new String[capacity];
        lats = new double[capacity];
        lons = new double[capacity];
        polygons = new Polygon[capacity];
    }

    @Override
    public int featureCount() {
        return ids.length;
    }

    @Override
    public double sumTotalOpportunities () {
        // FIXME this method is ill-defined on FreeFormPointSets which have more than one property. Grids and FreeForms are fundamentally different in this way.
        double total = 0;
        for (double[] magnitudes : properties.values()) {
            for (double n : magnitudes) {
                total += n;
            }
        }
        return total;
    }

    /**
     * Get the index of a particular feature ID in this pointset.
     * @return the index, or -1 if there is no such index.
     */
    public int getIndexForFeature(String featureId) {

        // this is called inside a conditional because the build method is synchronized,
        // and there is no need to synchronize if the map has already been built.
        if (idIndexMap == null)
            buildIdIndexMapIfNeeded();

        return idIndexMap.get(featureId);
    }

    /**
     * Build the ID - Index map if needed.
     */
    private synchronized void buildIdIndexMapIfNeeded () {
        // we check again if the map has been built. It's possible that it would have been built
        // by this method in another thread while this instantiation was blocked.
        if (idIndexMap == null) {
            // make a local object, don't expose to public view until it's built
            TObjectIntMap idIndexMap = new TObjectIntHashMap<String>(this.capacity, 1f, -1);

            for (int i = 0; i < this.capacity; i++) {
                if (ids[i] != null) {
                    if (idIndexMap.containsKey(ids[i])) {
                        LOG.error("Duplicate ID {} in pointset.", ids[i]);
                    }
                    else {
                        idIndexMap.put(ids[i], i);
                    }
                }
            }

            // now expose to public view; reference assignment is an atomic operation
            this.idIndexMap = idIndexMap;
        }
    }

    /**
     * Using getter methods here to allow generating coordinates and geometries on demand instead of storing them.
     * This would allow for implicit geometry, as in a regular grid of points.
     */
    @Override
    public double getLat(int i) {
        return lats[i];
    }

    @Override
    public double getLon(int i) {
        return lons[i];
    }

    /** Write coordinates for these points, in binary format. */
    public void write (OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);
        // Header
        // TODO add identifier / version for future sanity checking?
        out.writeUTF(description);
        out.writeUTF(label);
        out.writeInt(ids.length);
        for (String id : ids) {
            out.writeUTF(id);
        }
        for (double lat : lats) {
            out.writeDouble(lat);
        }
        for (double lon : lons) {
            out.writeDouble(lon);
        }

        out.close();
    }

    public FreeFormPointSet (InputStream inputStream) throws  IOException {
        DataInputStream data = new DataInputStream(inputStream);
        this.description = data.readUTF();
        this.label = data.readUTF();
        this.capacity = data.readInt();
        this.ids = new String[this.capacity];
        this.lats = new double[this.capacity];
        this.lons = new double[this.capacity];

        for (int i = 0; i < capacity; i++) {
            ids[i] = data.readUTF();
        }

        for (int i = 0; i < capacity; i++) {
            lats[i] = data.readDouble();
        }

        for (int i = 0; i < capacity; i++) {
            lons[i] = data.readDouble();
        }

        data.close();

    }

    @Override
    public double getOpportunityCount (int i) {
        // FIXME just counting the points for now, return counts
        return 1D;
    }

    public String getPointId (int i) {
        return ids[i];
    }

}
