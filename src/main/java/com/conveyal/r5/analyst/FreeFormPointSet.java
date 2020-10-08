package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.util.InputStreamProvider;
import com.csvreader.CsvReader;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * These are points serving as origins or destinations in an accessibility analysis which are not constrained to
 * a regular grid. Each point has an arbitrary latitude and longitude attached to it.
 * This class re-uses some of the legacy code, which was removed in R5 PR #338.
 */
public class FreeFormPointSet extends PointSet {
    private static final Logger LOG = LoggerFactory.getLogger(FreeFormPointSet.class);

    /** A unique identifier for each feature. */
    private final String[] ids;

    /** The latitude of each point. */
    private final double[] lats;

    /** The longitude of each point. */
    private final double[] lons;

    /** The number of opportunities located at each point. */
    private final double[] counts;

    // TODO check that all identifiers are unique

    /**
     * Create a FreeFormPointset from a CSV file, which must have latitude and longitude columns with the values of
     * latField and lonField in the header row. If idField is supplied, its column will be used to supply id values
     * for the points; if not, row numbers will be used as the ids.
     */
    public static FreeFormPointSet fromCsv (
            InputStreamProvider csvInputStreamProvider,
            String latField,
            String lonField,
            String idField,
            String countField
    ) throws IOException {
        /* First, scan through the file to count lines and check for rows with the wrong number of columns. */
        int nRecs;
        int latCol = -1;
        int lonCol = -1;
        int idCol = -1;
        int countCol = -1;
        try (InputStream csvInputStream = csvInputStreamProvider.getInputStream()) {
            CsvReader reader = new CsvReader(csvInputStream, ',', StandardCharsets.UTF_8);
            reader.readHeaders();
            int nCols = reader.getHeaderCount();
            for (int c = 0; c < nCols; c++) {
                String header = reader.getHeader(c);
                if (header.equals(latField)) {
                    latCol = c;
                } else if (header.equalsIgnoreCase(lonField)) {
                    lonCol = c;
                } else if (header.equalsIgnoreCase(idField)) {
                    idCol = c;
                } else if (header.equalsIgnoreCase(countField)) {
                    countCol = c;
                }
            }
            if (latCol < 0 || lonCol < 0) {
                throw new ParameterException("CSV file did not contain the specified latitude or longitude column.");
            }
            if (idField != null && idCol < 0) {
                throw new ParameterException("CSV file did not contain the specified ID column.");
            }
            if (idField != null && idCol < 0) {
                throw new ParameterException("CSV file did not contain the specified opportunity count column.");
            }
            while (reader.readRecord()) {
                if (reader.getColumnCount() != nCols) {
                    String message = String.format(
                            "CSV header has %d fields, record %d has %d fields.",
                            nCols,
                            reader.getCurrentRecord(),
                            reader.getColumnCount()
                    );
                    throw new ParameterException(message);
                }
            }
            // getCurrentRecord is zero-based and does not include headers or blank lines
            // FIXME isn't this creating one record too many, and leaving it blank? Verify.
            nRecs = (int) reader.getCurrentRecord() + 1;
        }

        /* If we reached here, the file is entirely readable. Re-read it from the beginning and record values. */
        // Note that we're doing two passes just so we know the array size. We could just use TIntLists.
        int rec = -1;
        try (InputStream csvInputStream = csvInputStreamProvider.getInputStream()) {
            CsvReader reader = new CsvReader(csvInputStream, ',', StandardCharsets.UTF_8);
            FreeFormPointSet ret = new FreeFormPointSet(nRecs);
            ret.name = countField != null ? countField : "[COUNT]";
            reader.readHeaders();
            while (reader.readRecord()) {
                rec = (int) reader.getCurrentRecord();
                ret.lats[rec] = Double.parseDouble(reader.get(latCol));
                ret.lons[rec] = Double.parseDouble(reader.get(lonCol));
                // If ID column was specified and present, use it. Otherwise, use record number as ID.
                ret.ids[rec] = idCol < 0 ? String.valueOf(rec) : reader.get(idCol);
                // If count column was specified and present, use it. Otherwise, one opportunity per point.
                ret.counts[rec] = countCol < 0 ? 1D : Double.parseDouble(reader.get(countCol));
            }
            Grid.checkWgsEnvelopeSize(ret.getWgsEnvelope());
            return ret;
        } catch (NumberFormatException nfe) {
            throw new ParameterException(
                String.format("Improperly formatted floating point value on line %d of CSV input", rec)
            );
        }
    }


    /**
     * @param capacity expected number of features to be added to this FreeFormPointSet.
     */
    private FreeFormPointSet(int capacity) {
        ids = new String[capacity];
        lats = new double[capacity];
        lons = new double[capacity];
        counts = new double[capacity];
    }

    @Override
    public int featureCount() {
        return ids.length;
    }

    @Override
    public double sumTotalOpportunities () {
        // For now we always have one opportunity per point.
        return featureCount();
    }

    @Override
    public double getLat(int i) {
        return lats[i];
    }

    @Override
    public double getLon(int i) {
        return lons[i];
    }

    /**
     * Write coordinates for these points, in binary format.
     * Note that this does not save any opportunity magnitudes or densities. We do not use those yet.
     * Note also that if we ever intend to use these directly in the UI we should switch to a
     * fixed-width little-endian representation or JSON.
     */
    public void write (OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);
        // Header
        // TODO add identifier / version for future sanity checking?
        // Should name and description be here or in Mongo metadata?
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
        for (double count : counts) {
            out.writeDouble(count);
        }
        out.close();
    }

    public FreeFormPointSet (InputStream inputStream) throws  IOException {
        DataInputStream data = new DataInputStream(inputStream);
        int nPoints = data.readInt();
        this.ids = new String[nPoints];
        this.lats = new double[nPoints];
        this.lons = new double[nPoints];
        this.counts = new double[nPoints];
        for (int i = 0; i < nPoints; i++) {
            ids[i] = data.readUTF();
        }
        for (int i = 0; i < nPoints; i++) {
            lats[i] = data.readDouble();
        }
        for (int i = 0; i < nPoints; i++) {
            lons[i] = data.readDouble();
        }
        for (int i = 0; i < nPoints; i++) {
            counts[i] = data.readDouble();
        }
        data.close();
    }

    @Override
    public TIntList getPointsInEnvelope (Envelope envelopeFixedDegrees) {
        // Convert fixed-degree envelope to floating
        double west = fixedDegreesToFloating(envelopeFixedDegrees.getMinX());
        double east = fixedDegreesToFloating(envelopeFixedDegrees.getMaxX());
        double north = fixedDegreesToFloating(envelopeFixedDegrees.getMaxY());
        double south = fixedDegreesToFloating(envelopeFixedDegrees.getMinY());
        TIntList pointsInEnvelope = new TIntArrayList();
        // Pixels are truncated toward zero, and coords increase toward East and South in web Mercator, so <= south/east.
        for (int i = 0; i < lats.length; i++) {
            if (lats[i] < north && lats[i] > south && lons[i] < east && lons[i] > west) pointsInEnvelope.add(i);
        }
        return pointsInEnvelope;
    }

    @Override
    public double getOpportunityCount (int i) {
        // For now, these points do not have attached opportunity counts.
        // We consider them to all have a count of 1.
        return 1D;
    }

    @Override
    public String getId (int i) {
        return ids[i];
    }

    @Override
    public Envelope getWgsEnvelope () {
        if (lats.length == 1 || lons.length == 0) {
            LOG.error("Attempt to create envelope from empty lat/lon array.");
            return null;
        }
        double minLat = Arrays.stream(lats).min().getAsDouble();
        double minLon = Arrays.stream(lons).min().getAsDouble();
        double maxLat = Arrays.stream(lats).max().getAsDouble();
        double maxLon = Arrays.stream(lons).max().getAsDouble();
        Envelope envelope = new Envelope(minLon, maxLon, minLat, maxLat);
        return envelope;
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents () {
        final int DEFAULT_ZOOM = 9;
        Envelope wgsEnvelope = this.getWgsEnvelope();
        WebMercatorExtents webMercatorExtents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, DEFAULT_ZOOM);
        return webMercatorExtents;
    }

}
