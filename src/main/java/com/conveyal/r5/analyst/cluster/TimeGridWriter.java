package com.conveyal.r5.analyst.cluster;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.FastRaptorWorker;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.media.jai.RasterFactory;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Given a TravelTimeResult containing travel times from one origin to NxM gridded destinations, this class will write
 * them out to various file formats. Output is multi-channel: for each destination, it saves one or more percentiles of
 * the travel time distribution. Supported formats are the Conveyal internal binary format and GeoTIFF for
 * interoperability with desktop GIS.
 * <p>
 * The Conveyal binary format is similar to the Grid format used for opportunity grids, but it uses ints for the pixel
 * values, and allows multiple values per pixel. It is now identical to the AccessGrid format, which is why its header
 * now says ACCESSGR instead of TIMEGRID.
 * <p>
 * TODO Unify this class with GridResultWriter in the backend, as they both write the same format now.
 *      We should be able to remove GridResultWriter from the backend and use this class imported from R5.
 *      We may eventually also be able to store opportunities in the same format.
 *      Prefiltering could aid compression (delta coding, skipping over or not delta coding UNREACHED values).
 * <p>
 * Time grids are composed of little-endian signed 4-byte ints, so the full grid can be mapped into a Javascript typed
 * array of 4-byte integers if desired. They are laid out as follows:
 * <ol>
 * <li>(8 bytes) Magic numbers: ASCII text "ACCESSGR". This is a multiple of four bytes to maintain alignment.</li>
 * <li>(4 byte int) File format version</li>
 * <li>(4 byte int) Web mercator zoom level</li>
 * <li>(4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world</li>
 * <li>(4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world</li>
 * <li>(4 byte int) width of the grid in pixels</li>
 * <li>(4 byte int) height of the grid in pixels</li>
 * <li>(4 byte int) number of values (channels) per pixel</li>
 * <li>(repeated 4-byte int) values of each pixel in row-major order: axis order (row, column, channel).
 *     Values are not delta-coded.</li>
 * </ol>
 */
public class TimeGridWriter {

    public static final Logger LOG = LoggerFactory.getLogger(TimeGridWriter.class);

    /** 8 bytes long to maintain integer alignment. */
    private static final String gridType = "ACCESSGR";

    /** The offset to get to the data section of the access grid file. The gridType string amounts to 2 ints. */
    public static final int HEADER_SIZE = 7 * Integer.BYTES + gridType.getBytes().length;

    private static final int version = 0;

    private final TravelTimeResult travelTimeResult;

    private final AnalysisWorkerTask analysisWorkerTask;

    private final WebMercatorExtents extents;

    private final long nIntegersInOuput;

    private final long nBytesInOutput; // specifically for Conveyal internal format

    /**
     * Create a new in-memory time grid writer for the supplied TravelTimeResult, which is interpreted as a
     * rectangular grid matching the supplied WebMercatorExtents.
     */
    public TimeGridWriter (TravelTimeResult travelTimeResult, AnalysisWorkerTask analysisWorkerTask) {
        this.travelTimeResult = travelTimeResult;
        this.analysisWorkerTask = analysisWorkerTask;
        this.extents = WebMercatorExtents.forTask(analysisWorkerTask);
        if (travelTimeResult.nPoints != extents.width * extents.height) {
            throw new ParameterException("Travel time cannot be for a grid of this dimension.");
        }
        nIntegersInOuput = (long) travelTimeResult.nSamplesPerPoint * travelTimeResult.nPoints;
        nBytesInOutput = nIntegersInOuput * Integer.BYTES + HEADER_SIZE;
        if (nBytesInOutput > Integer.MAX_VALUE) {
            throw new RuntimeException("Grid size in bytes exceeds 31-bit addressable space.");
        }
    }

    /**
     * Write the grid to an object implementing the DataOutput interface. Note that the endianness of the integers
     * in the output will be dependent on the DataOutput implementation supplied. To fit our file format
     * specification the DataOutput should be little-endian.
     *
     * TODO maybe shrink the dimensions of the resulting timeGrid to contain only the reached cells.
     */
    public void writeToDataOutput(DataOutput dataOutput) {
        LOG.info("Writing travel time surface with uncompressed size {} kiB", nBytesInOutput / 1024);
        try {
            // Write header
            dataOutput.write(gridType.getBytes());
            dataOutput.writeInt(version);
            dataOutput.writeInt(extents.zoom);
            dataOutput.writeInt(extents.west);
            dataOutput.writeInt(extents.north);
            dataOutput.writeInt(extents.width);
            dataOutput.writeInt(extents.height);
            dataOutput.writeInt(travelTimeResult.nSamplesPerPoint);
            // Write values, delta coded
            for (int i = 0; i < travelTimeResult.nSamplesPerPoint; i++) {
                int prev = 0; // delta code within each percentile grid
                for (int j = 0; j < travelTimeResult.nPoints; j++) {
                    int curr = travelTimeResult.values[i][j];
                    // TODO try not delta-coding the "unreachable" value, and retaining the previous value across
                    //  unreachable areas of the grid.
                    int delta = curr - prev;
                    dataOutput.writeInt(delta);
                    prev = curr;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Write the grid out to a persistence buffer, an abstraction that will perform compression and allow us to
     * save it to a local or remote storage location. Note that the PersistenceBuffer's dataOutput is
     * little-endian.
     */
    public PersistenceBuffer writeToPersistenceBuffer() {
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        this.writeToDataOutput(persistenceBuffer.getDataOutput());
        persistenceBuffer.doneWriting();
        return persistenceBuffer;
    }

    /**
     * Write this grid out in GeoTIFF format.
     * If an analysis task is supplied, add metadata to the GeoTIFF explaining what scenario it comes from.
     */
    public void writeGeotiff (OutputStream out) {
        LOG.info("Writing GeoTIFF file");
        try {
            // Inspired by org.geotools.coverage.grid.GridCoverageFactory
            final WritableRaster raster = RasterFactory.createBandedRaster(
                DataBuffer.TYPE_INT,
                extents.width,
                extents.height,
                travelTimeResult.nSamplesPerPoint,
                null
            );

            for (int y = 0, val; y < extents.height; y++) {
                for (int x = 0; x < extents.width; x++) {
                    for (int n = 0; n < travelTimeResult.nSamplesPerPoint; n++) {
                        val = travelTimeResult.values[n][(y * extents.width + x)];
                        if (val < FastRaptorWorker.UNREACHED) raster.setSample(x, y, n, val);
                    }
                }
            }

            GridCoverageFactory gcf = new GridCoverageFactory();
            GridCoverage2D coverage = gcf.create("TIMEGRID", raster, extents.getMercatorEnvelopeMeters());
            GeoTiffWriteParams wp = new GeoTiffWriteParams();
            wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            wp.setCompressionType("LZW");
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(wp);

            GeoTiffWriter writer = new GeoTiffWriter(out);

            // If the request that produced this TimeGrid was supplied, write scenario metadata into the GeoTIFF
            if (analysisWorkerTask != null) {
                AnalysisWorkerTask clonedRequest = analysisWorkerTask.clone();
                // Save the scenario ID rather than the full scenario, to avoid making metadata too large. We're not
                // losing information here, the scenario id used here is qualified with the CRC and is thus immutable
                // and available from S3.
                if (clonedRequest.scenario != null) {
                    clonedRequest.scenarioId = clonedRequest.scenario.id;
                    clonedRequest.scenario = null;
                }
                // 270: Image Description, 305: Software (https://www.awaresystems.be/imaging/tiff/tifftags/baseline.html)
                writer.setMetadataValue("270", JsonUtilities.objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(clonedRequest));
                writer.setMetadataValue("305", "Conveyal R5");
            }
            writer.write(coverage, params.values().toArray(new GeneralParameterValue[1]));
            writer.dispose();
        } catch (Exception e) {
            throw new RuntimeException("Failed to write GeoTIFF file.", e);
        }

    }

    /**
     * Write this grid out in PNG format. The first three percentiles will be stored in the RGB channels as minutes.
     * The geographic bounds are included as a text tag on the PNG. Other information like the proportion of iterations
     * in which each destination is reachable could be encoded in the alpha channel of the PNG.
     *
     * ImageIO PNG writing is said to be slow, and external libraries exist that are several times faster. However,
     * this speedup seems to be achieved via multi-threaded compression. On a worker machine where all cores are often
     * occupied with multiple requests in parallel, it may not be very advantageous to spread the work of each request
     * across multiple threads.
     *
     * The metadata writing process with ImageIO is so complex that the right approach is probably to use the tiny
     * https://github.com/leonbloy/pngj/ which allows setting metadata and writing the file in only a couple of lines.
     */
    public void writePng (OutputStream out) {
        BufferedImage image = new BufferedImage(extents.width, extents.height, BufferedImage.TYPE_INT_RGB);
        WritableRaster raster = image.getRaster();
        int[] rgb = new int[3];
        for (int y = 0; y < extents.height; y++) {
            for (int x = 0; x < extents.width; x++) {
                for (int n = 0; n < 3; n++) {
                    rgb[n] = travelTimeResult.values[n][y * extents.width + x];
                }
                raster.setPixel(x, y, rgb);
            }
        }
        // Add a single PNG text chunk containing the WGS84 bounding box. See: https://www.w3.org/TR/png/#11textinfo
        // IIOImage is "a container class to aggregate an image, a set of thumbnail images, and... metadata associated
        // with the image". The ImageIO methods for manipulating metadata seem quite convoluted and use the XML DOM API.
        // Hopefully decoding and using this metadata will be less complex on the receiving end.
        // Below is based on ImageIO.write(), https://coderanch.com/t/750185/java/Writing-PNG-images-output-file and
        // https://stackoverflow.com/a/8735707/778449
        ImageWriter writer = ImageIO.getImageWritersByFormatName("PNG").next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(image.getType());
        IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
        IIOMetadataNode textEntry = new IIOMetadataNode("TextEntry");
        textEntry.setAttribute("keyword", "bbox");
        textEntry.setAttribute("value", extents.toWgsBboxHeaderString());
        IIOMetadataNode text = new IIOMetadataNode("Text");
        text.appendChild(textEntry);
        IIOMetadataNode root = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName);
        root.appendChild(text);
        try {
            metadata.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, root);
            IIOImage iioImage = new IIOImage(image, null, metadata);
            writer.setOutput(ImageIO.createImageOutputStream(out));
            writer.write(iioImage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}