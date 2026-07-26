package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.r5.transit.GtfsTransferLoader;
import com.conveyal.r5.transit.TransitLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

import static com.conveyal.r5.analyst.cluster.TransportNetworkConfig.TransferConfig.OSM_ONLY;

/// R5 IndexedPolygonCollection and ModificationPolygon use the JTS Polygonal interface (which is
/// not a Geometry).
///
/// GeoTools has some fairly heavy abstractions and poses serialization difficulties. Specifically,
/// GeoTools is generic across different coordinate storage schemes, spatial reference systems, and
/// precision models, and will attempt to store object graphs capturing this informtion for every
/// single geometry instance. We can sidestep some of the heavier by using our own lightweight
/// geometry classes. This requires us to replicate a lot of GeoTools GeoJSON loading capabilities,
/// but we take that opportunity to parse in an entirely streaming manner instead of buffering in
/// memory and using the tree model.
///
/// ModificationPolygon associates a Polygonal with a unique ID and a rider-facing name.
/// Consider that there are typically only a few zones per file and all coordinates must be
/// loaded in memory for conversion to a JTS geometry, so streaming is not a critical optimization.
/// But a streaming loader is efficient and relevant here, and will be heavily reusable.
///
/// Initially we were calling into GeoJsonModule (the Jackson parsing module from BeDataDriven)
/// which calls GeometryDeserializer.parseGeometry() and is registered with JsonUtil.objectMapper.
/// But that tree-parses one geometry at a time, not a FeatureCollection, so we needed to stream
/// parse the outer structure anyway and read fragments with with JsonUtil.objectMapper.readValue();
public abstract class StreamingFlexLocationLoader {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Main method for use in development and testing. Load a flex feed and build
    /// This should be turned into a real Java test in the final work product.
    public static void main (String[] args) throws Exception {
        String zipFileName = args[0];
        GTFSFeed gtfs = GTFSFeed.readOnlyTempFileFromGtfs(zipFileName);
        TransitLayer transitLayer = new TransitLayer();
        transitLayer.loadFromGtfs(gtfs, new GtfsTransferLoader(transitLayer, OSM_ONLY));
        gtfs.close();
    }


}
