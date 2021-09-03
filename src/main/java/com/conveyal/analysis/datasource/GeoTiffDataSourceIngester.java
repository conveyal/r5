package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.r5.analyst.progress.ProgressListener;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.util.factory.Hints;
import org.opengis.coverage.SampleDimensionType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.conveyal.file.FileStorageFormat.GEOTIFF;
import static com.conveyal.r5.util.ShapefileReader.GeometryType.PIXEL;

/**
 * GoeTIFFs are used as inputs in network building as digital elevation profiles, and eventually expected to
 * serve as impedance or cost fields (e.g. shade bonus and pollution malus).
 */
public class GeoTiffDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    public GeoTiffDataSourceIngester () {
        this.dataSource = new SpatialDataSource();
        dataSource.geometryType = PIXEL;
        dataSource.fileFormat = GEOTIFF; // Should be GEOTIFF specifically
    }

    @Override
    protected DataSource dataSource () {
        return dataSource;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        progressListener.beginTask("Processing uploaded GeoTIFF", 1);
        AbstractGridFormat format = GridFormatFinder.findFormat(file);
        Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
        var coverageReader = format.getReader(file, hints);
        GridCoverage2D coverage;
        try {
             coverage = coverageReader.read(null);
        } catch (IOException e) {
            throw new DataSourceException("Could not read GeoTiff.", e);
        }
        // Transform to WGS84 to ensure this will not trigger any errors downstream.
        CoordinateReferenceSystem coverageCrs = coverage.getCoordinateReferenceSystem2D();
        MathTransform wgsToCoverage, coverageToWgs;
        ReferencedEnvelope wgsEnvelope;
        try {
            wgsToCoverage = CRS.findMathTransform(DefaultGeographicCRS.WGS84, coverageCrs);
            coverageToWgs = wgsToCoverage.inverse();
            // Envelope in coverage CRS is not necessarily aligned with axes when transformed to WGS84.
            // As far as I can tell those cases are handled by this call, but I'm not completely sure.
            wgsEnvelope = new ReferencedEnvelope(coverage.getEnvelope2D().toBounds(DefaultGeographicCRS.WGS84));
        } catch (FactoryException | TransformException e) {
            throw new DataSourceException("Could not create coordinate transform to and from WGS84.");
        }

        List<SpatialAttribute> attributes = new ArrayList<>();
        for (int d = 0; d < coverage.getNumSampleDimensions(); d++) {
            GridSampleDimension sampleDimension = coverage.getSampleDimension(d);
            SampleDimensionType type = sampleDimension.getSampleDimensionType();
            attributes.add(new SpatialAttribute(type, d));
        }
        // Get the dimensions of the pixel grid so we can record the number of pixels.
        // Total number of pixels can be huge, cast it to 64 bits.
        GridEnvelope2D gridEnv = coverage.getGridGeometry().getGridRange2D();
        dataSource.wgsBounds = Bounds.fromWgsEnvelope(wgsEnvelope);
        dataSource.featureCount = (long)gridEnv.width * (long)gridEnv.height;
        dataSource.geometryType = PIXEL;
        dataSource.attributes = attributes;
        dataSource.coordinateSystem = coverage.getCoordinateReferenceSystem().getName().getCode();
        progressListener.increment();
    }

}
