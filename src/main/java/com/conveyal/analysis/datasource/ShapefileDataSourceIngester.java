package com.conveyal.analysis.datasource;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.io.FilenameUtils;
import org.locationtech.jts.geom.Envelope;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

public class ShapefileDataSourceIngester extends DataSourceIngester<SpatialDataSource> {

    public ShapefileDataSourceIngester (
        FileStorage fileStorage,
        AnalysisCollection<DataSource> dataSourceCollection,
        List<FileItem> fileItems,
        UserPermissions userPermissions
    ) {
        super(fileStorage, dataSourceCollection, fileItems);
        dataSource = new SpatialDataSource(userPermissions, "NONE");
        dataSource.fileFormat = FileStorageFormat.SHP;
    }

    @Override
    public void ingest (ProgressListener progressListener) {
        progressListener.beginTask("Validating files", 1);
        // In the caller, we should have already verified that all files have the same base name and have an extension.
        // Extract the relevant files: .shp, .prj, .dbf, and .shx.
        Map<String, File> filesByExtension = new HashMap<>();
        for (File file : files) {
            filesByExtension.put(FilenameUtils.getExtension(file.getName()).toUpperCase(), file);
        }
        try {
            ShapefileReader reader = new ShapefileReader(filesByExtension.get("SHP"));
            Envelope envelope = reader.wgs84Bounds();
            checkWgsEnvelopeSize(envelope);
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(envelope);
            dataSource.attributes = reader.attributes();
            dataSource.geometryType = reader.geometryType();
            dataSource.featureCount = reader.featureCount();
        } catch (FactoryException | TransformException e) {
            throw new RuntimeException("Shapefile transform error. Try uploading an unprojected (EPSG:4326) file.", e);
        } catch (Exception e) {
            // Must catch because ShapefileReader throws a checked IOException.
            throw new RuntimeException("Error parsing shapefile. Ensure the files you uploaded are valid.", e);
        }
    }

}
