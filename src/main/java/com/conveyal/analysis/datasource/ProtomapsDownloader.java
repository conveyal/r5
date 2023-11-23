package com.conveyal.analysis.datasource;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.DataSourceIngester;
import com.conveyal.analysis.datasource.OsmDataSourceIngester;
import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.OsmDataSource;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.ProgressInputStream;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.TaskAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.types.ObjectId;
import org.hsqldb.rights.User;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static com.conveyal.r5.analyst.cluster.AnalysisWorker.sleepSeconds;


/**
 *
 */
public class ProtomapsDownloader implements TaskAction {

    // Protomaps API token from https://app.protomaps.com/dashboard
    private static final String PROTOMAPS_TOKEN = "YOUR TOKEN HERE";
    private static final String PROTOMAPS_EXTRACT_URL = "https://app.protomaps.com/api/v1/extracts";

    private final Region region;

    private final UserPermissions userPermissions;
    private final FileStorage fileStorage;
    private final AnalysisCollection<DataSource> dataSourceCollection;


    // CONSTRUCTOR

    // Note, it seems like ingester/downloader actions that create datasources should not themselves need
    // access to the DataSource instance, FileStorage etc. but handling this correctly might require sub-tasks.
    // DataSourceIngester has the beginnings of such a system, where it creates the data source itself.
    // See also insertion into database at com.conveyal.analysis.datasource.DataSourceUploadAction.action().
    // Perhaps we should download the OSMPBF and then just feed it to a standard OSM file ingester.
    // But we also don't want to be passing user permissions, database collections etc. into here.

    public ProtomapsDownloader(Region region, UserPermissions userPermissions, FileStorage fileStorage, AnalysisCollection<DataSource> dataSourceCollection) {
        this.region = region;
        this.userPermissions = userPermissions;
        this.fileStorage = fileStorage;
        this.dataSourceCollection = dataSourceCollection;
    }


    // Model object for JSON result of polling for extract progress
    // While extract is happening, response will look like this:
    //            {
    //                "Timestamp" : "2022-11-25T06:47:54Z",
    //                    "CellsTotal" : 712,
    //                    "CellsProg" : 712,
    //                    "NodesTotal" : 801359,
    //                    "NodesProg" : 801359,
    //                    "ElemsTotal" : 890865,
    //                    "ElemsProg" : 881991
    //            }
    // The cells will count up first, then nodes, then elems.
    // When it's finished the response will look like this:
    //            {
    //                "Uuid" : "d25e08bf-bc46-475c-aac1-397cddfd523f",
    //                    "Timestamp" : "2022-11-25T06:47:54Z",
    //                    "ElemsTotal" : 890865,
    //                    "Complete" : true,
    //                    "SizeBytes" : 6721639,
    //                    "Elapsed" : 2.053800663
    //            }
    public static class Progress {
        // Field capitalization is nonstandard - can objectmapper do case insensitive mapping?
        public String Uuid, Timestamp;
        public Double Elapsed; // in seconds
        public int CellsTotal, CellsProg, NodesTotal, NodesProg, ElemsTotal, ElemsProg;
        public boolean Complete;
        public long SizeBytes;
    }

    private ObjectNode newRequestBodyWithToken () {
        return JsonUtil.objectNode().put("token", PROTOMAPS_TOKEN);
    }

    @Override
    public void action(ProgressListener progressListener) throws Exception {
        progressListener.beginTask("Contacting Protomaps", 2);

        HttpClient httpClient = HttpClient.newBuilder().build();

        ObjectNode initialRequestBody = newRequestBodyWithToken();
        final Bounds bounds = region.bounds;
        initialRequestBody.putObject("region")
                .put("type", "bbox")
                .putArray("data")
                .add(bounds.south)
                .add(bounds.west)
                .add(bounds.north)
                .add(bounds.east);

        HttpRequest initialRequest = HttpRequest.newBuilder()
                .POST(BodyPublishers.ofString(initialRequestBody.toString()))
                .uri(URI.create(PROTOMAPS_EXTRACT_URL))
                .build();

        HttpResponse<byte[]> initialResponse = httpClient.send(initialRequest, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode initialJson = JsonUtil.objectMapper.readTree(initialResponse.body());
        final String uuid = initialJson.get("uuid").asText(); // ID of this extract
        URI progressUri = URI.create(initialJson.get("url").asText()); // for polling extract progress

        progressListener.beginTask("Extracting OSM data", 100);
        HttpRequest progressRequest = HttpRequest.newBuilder().GET().uri(progressUri).build();
        while (true) {
            HttpResponse<byte[]> response = httpClient.send(progressRequest, HttpResponse.BodyHandlers.ofByteArray());
            System.out.println(new String(response.body()));
            // JsonNode progressJson = JsonUtil.objectMapper.readTree(response.body());
            // System.out.println(progressJson.toPrettyString());
            Progress progress = JsonUtil.objectMapper.readValue(response.body(), Progress.class);
            if (progress.Complete) {
                break;
            }
            // Extraction proceeds in three phases: spatial index cells, then nodes, then ways and relations.
            // This is abusing our ProgressListener programming API (new task each iteration) but it works for now.
            if (progress.CellsProg < progress.CellsTotal) {
                progressListener.beginTask("Scanning cells", progress.CellsTotal);
                progressListener.increment(progress.CellsProg);
            } else if (progress.NodesProg < progress.NodesTotal) {
                progressListener.beginTask("Scanning nodes", progress.NodesTotal);
                progressListener.increment(progress.NodesProg);
            } else if (progress.ElemsProg < progress.ElemsTotal) {
                progressListener.beginTask("Scanning elements", progress.ElemsTotal);
                progressListener.increment(progress.ElemsProg);
            }
            sleepSeconds(1);
            // TODO timeout and stall detection (on all background tasks?)
        }

        // Get the download URL for the finished extract
        progressListener.beginTask("Getting download URL", 1);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .POST(BodyPublishers.ofString(newRequestBodyWithToken().toString()))
                .uri(URI.create(PROTOMAPS_EXTRACT_URL + "/" + uuid))
                .build();

        HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode downloadJson = JsonUtil.objectMapper.readTree(downloadResponse.body());
        System.out.println(downloadJson.toPrettyString());
        URI downloadUri = URI.create(downloadJson.get("url").asText());

        // Finally, actually download the PBF data and save it to a temp file.
        // Don't use simple downloadUri.toURL().openStream() because we want the content length header
        progressListener.beginTask("Retrieving OSM data", 1);
        HttpRequest fileRequest = HttpRequest.newBuilder()
                .GET()
                .uri(downloadUri)
                .build();

        HttpResponse<InputStream> fileResponse = httpClient.send(fileRequest, HttpResponse.BodyHandlers.ofInputStream());

        Path tempFile = Files.createTempFile("protomaps_extract", ".osm.pbf");

        // Sizes over 2GB are entirely possible, should really use longs
        long contentLength = fileResponse.headers().firstValueAsLong("Content-Length").orElse(100_000_000);
        progressListener.beginTask("Retrieving OSM data", (int) contentLength);
        try (InputStream inputStream = new ProgressInputStream(progressListener, fileResponse.body())) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // The rest of this is replicating some logic from DataSourceUploadAction and DataSourceIngester,
        // which need to be generalized to files that are downloaded by the backend rather than just uploaded to it.

        DataSourceIngester ingester = new OsmDataSourceIngester();
        ingester.initializeDataSource(region.name, tempFile.toString(), region._id, userPermissions);
        DataSource dataSource = ingester.dataSource();
        dataSource.wgsBounds = region.bounds;
        FileStorageKey key = new FileStorageKey(FileCategory.DATASOURCES, dataSource._id.toString());
        fileStorage.moveIntoStorage(key, tempFile.toFile());
        ingester.ingest(fileStorage.getFile(key), progressListener);
        progressListener.setWorkProduct(ingester.dataSource().toWorkProduct());
        dataSourceCollection.insert(dataSource);

    }

}
