package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Stream GTFS Flex locations (GeoJSON polygons) into a (typically disk-backed) Map keyed on ID.
/// Though this loads a GTFS table, it does not use Entity.Loader because that assumes files are
/// CSV and their file names end in .txt. Rather than buffer all the objects in memory, we store
/// them one by one as they are decoded.
public class FlexLocationStreamer extends GeoJsonStreamer {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Map<String, FlexLocation> targetMap;

    public FlexLocationStreamer (InputStream inputStream, Map<String, FlexLocation> targetMap) {
        super(inputStream);
        this.targetMap = targetMap;
    }

    @Override
    void readOneFeature () throws IOException {
        expectCurrent(JsonToken.START_OBJECT);
        String id = null;
        String name = null;
        CPolygon cPolygon = null;
        while (jp.nextToken() != JsonToken.END_OBJECT) {
            switch (expectCurrentFieldName()) {
                case "type" -> expectNextString("Feature");
                case "id" -> id = expectNextString();
                case "properties" -> {
                    expectNext(JsonToken.START_OBJECT);
                    while (jp.nextToken() != JsonToken.END_OBJECT) {
                        String key = expectCurrentFieldName();
                        String val = expectNextString();
                        // Oddly, the name of a location (which is a zone and never a stop) is called stopName.
                        if ("stop_name".equals(key)) name = val;
                    }
                }
                case "geometry" -> cPolygon = streamOnePolygon();
            }
        }
        cPolygon.validate();
        targetMap.put(id, new FlexLocation(id, name, null, cPolygon));
    }

    public static void loadLocationsJson (ZipFile zip, Map<String, FlexLocation> map) throws Exception {
        ZipEntry entry = zip.getEntry("locations.geojson");
        if (entry == null) {
            LOG.info("GTFS feed does not have locations.geojson specifying flex zones.");
            return;
        }
        InputStream inStream = zip.getInputStream(entry);
        // GTFS reference says pick-up and drop-off zones in locations.geojson are always polygonal.
        // The features are allowed to be a mix of Polygon and MultiPolygon types.
        // The top level must be a FeatureCollection and every feature must have a string ID.
        // GTFS allows the UTF byte order mark in files, so we need to handle it.
        // JSON allows only UTF-8/16/32, which the Jackson streaming JsonParser auto-detects:
        // ByteSourceJsonBootstrapper.constructParser calls detectEncoding which has BOM handling.
        // The Jackson streaming JSON API sacrifices readability for speed and memory so is not
        // always ideal. Here the JSON structure is simple enough that it works cleanly.
        // When reading into the tree model instead of streaming, objectMapper.readTree calls
        // createParser which calls constructParser indirectly benefitting from its BOM handling.
        new FlexLocationStreamer(inStream, map).stream();
    }

}
