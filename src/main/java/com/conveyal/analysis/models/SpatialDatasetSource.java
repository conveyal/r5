package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.spatial.SpatialDataset.GeometryType;
import com.conveyal.analysis.spatial.SpatialDataset.SourceFormat;
import com.conveyal.r5.util.ShapefileReader;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.io.Files;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.locationtech.jts.geom.Envelope;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

public class SpatialDatasetSource extends BaseModel {
    public List<FileInfo> files;
    public String regionId;
    public String description;
    public SourceFormat sourceFormat;
    public GeometryType geometryType;
    public Map<String, Class> attributes; // TODO map to both type and user-modifiable label?
    public int featureCount;

    private SpatialDatasetSource (UserPermissions userPermissions, String sourceName) {
        super(userPermissions, sourceName);
    }

    @JsonIgnore
    public static SpatialDatasetSource create (UserPermissions userPermissions, String sourceName) {
        return new SpatialDatasetSource(userPermissions, sourceName);
    }

    @JsonIgnore
    public SpatialDatasetSource withRegion (String regionId) {
        this.regionId = regionId;
        return this;
    }

    @JsonIgnore
    public SpatialDatasetSource fromShapefile (List<FileItem> fileItems) throws Exception {
        // In the caller, we should have already verified that all files have the same base name and have an extension.
        // Extract the relevant files: .shp, .prj, .dbf, and .shx.
        // We need the SHX even though we're looping over every feature as they might be sparse.
        Map<String, FileItem> filesByExtension = new HashMap<>();
        for (FileItem fileItem : fileItems) {
            filesByExtension.put(FilenameUtils.getExtension(fileItem.getName()).toUpperCase(), fileItem);
        }

        // Copy the shapefile component files into a temporary directory with a fixed base name.
        File tempDir = Files.createTempDir();

        File shpFile = new File(tempDir, "grid.shp");
        filesByExtension.get("SHP").write(shpFile);

        File prjFile = new File(tempDir, "grid.prj");
        filesByExtension.get("PRJ").write(prjFile);

        File dbfFile = new File(tempDir, "grid.dbf");
        filesByExtension.get("DBF").write(dbfFile);

        // The .shx file is an index. It is optional, and not needed for dense shapefiles.
        if (filesByExtension.containsKey("SHX")) {
            File shxFile = new File(tempDir, "grid.shx");
            filesByExtension.get("SHX").write(shxFile);
        }

        ShapefileReader reader = new ShapefileReader(shpFile);
        Envelope envelope = reader.wgs84Bounds();
        checkWgsEnvelopeSize(envelope);

        this.attributes = reader.getAttributeTypes();
        this.sourceFormat = SourceFormat.SHAPEFILE;
        // TODO this.geometryType = 
        return this;
    }

    @JsonIgnore
    public SpatialDatasetSource fromFiles (List<FileItem> fileItemList) {
        // TODO this.files from fileItemList;
        return this;
    }

}
