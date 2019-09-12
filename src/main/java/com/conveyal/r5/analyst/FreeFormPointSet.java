package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.error.UnsupportedGeometryException;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.csvreader.CsvReader;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.geojson.LngLatAlt;
import org.geotools.data.FileDataStore;
import org.geotools.data.FileDataStoreFinder;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.referencing.CRS;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * PointSets serve as groups of destinations or origins. They were a key resource in the legacy web analyst
 * (aka OTP Analyst aka TransportAnalyst). For the first couple years of the new Conveyal Analysis, only
 * WebMercatorGridPointSets were supported. This class re-enables non-gridded pointsets, re-using/modifying some of the
 * legacy code, which was removed in R5 PR #338.
 */
public class FreeFormPointSet extends PointSet implements Serializable {

    private static final long serialVersionUID = -8962916330731463238L;

    private static final Logger LOG = LoggerFactory.getLogger(FreeFormPointSet.class);

    /** A server-unique identifier for this FreeFormPointSet */
    public String id;

    /** A short description of this FreeFormPointSet for use in a legend or menu */
    public String label;

    /** A detailed textual description of this FreeFormPointSet */
    public String description;

    public Map<String, int[]> properties = new HashMap<String, int[]>();

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
     * Rather than trying to load anything and everything, we stick to a strict format and rely on other tools to get
     * the data into the correct format.
     * This includes headers and coordinates in WGS84. Comment lines are allowed in these input files, and begin with
     * a #.
     */
    public static FreeFormPointSet fromCsv(File filename) throws IOException {
        /* First, scan through the file to count lines and check for errors. */
        CsvReader reader = new CsvReader(filename.getAbsolutePath(), ',', Charset.forName("UTF8"));
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
        reader.close();
        /* If we reached here, the file is entirely readable. Start over. */
        reader = new CsvReader(filename.getAbsolutePath(), ',', Charset.forName("UTF8"));
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

        // Map columns to property names by position in this array. Extra column for "count"
        String[] propertyNames = new String[nCols + 1];

        // An array of property magnitudes corresponding to each column in the input.
        // Some of these will remain null (specifically, the lat and lon columns which do not contain magnitudes)
        int[][] properties = new int[nCols + 1][ret.capacity];
        for (int c = 0; c < nCols; c++) {
            String header = reader.getHeader(c);
            if (header.equalsIgnoreCase("lat") || header.equalsIgnoreCase("latitude")) {
                latCol = c;
            } else if (header.equalsIgnoreCase("lon") || header.equalsIgnoreCase("longitude")) {
                lonCol = c;
            } else if (header.equalsIgnoreCase("Point") || header.equalsIgnoreCase("id")) {
                idCol = c;
            } else {
                propertyNames[c] = header;
                properties[c] = new int[ret.capacity];
            }
        }
        if (latCol < 0 || lonCol < 0) {
            LOG.error("CSV file did not contain a latitude or longitude column.");
            throw new IOException();
        }
        if (idCol < 0) {
            LOG.error("CSV file did not contain an ID column.");
            throw new IOException();
        }
        ret.ids = new String[nRecs];
        ret.lats = new double[nRecs];
        ret.lons = new double[nRecs];

        propertyNames[nCols] = "Count";
        properties[nCols] = new int[ret.capacity];

        while (reader.readRecord()) {
            int rec = (int) reader.getCurrentRecord();
            properties[nCols][rec] = 1; // count column has value 1 for each record
            for (int c = 0; c < nCols; c++) {
                if(c==latCol || c==lonCol || c == idCol){
                    continue;
                }
                int[] prop = properties[c];
                int mag = Integer.parseInt(reader.get(c));
                prop[rec] = mag;
            }
            ret.ids[rec] = reader.get(idCol);
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

    // Not yet re-implemented
    public static FreeFormPointSet fromShapefile(File file) throws NoSuchAuthorityCodeException, IOException,
            FactoryException, IllegalArgumentException {
        return fromShapefile(file, null, null);
    }

    // Not yet re-implemented
    public static FreeFormPointSet fromShapefile(File file, String originIDField, List<String> propertyFields) throws IOException, FactoryException, IllegalArgumentException {
        if ( ! file.exists())
            throw new RuntimeException("Shapefile does not exist.");

        FileDataStore store = FileDataStoreFinder.getDataStore(file);
        SimpleFeatureSource featureSource = store.getFeatureSource();

        CoordinateReferenceSystem sourceCRS = featureSource.getInfo().getCRS();
        CoordinateReferenceSystem WGS84 = CRS.decode("EPSG:4326", true);

        Query query = new Query();
        query.setCoordinateSystem(sourceCRS);
        query.setCoordinateSystemReproject(WGS84);
        SimpleFeatureCollection featureCollection = featureSource.getFeatures(query);

        // Set up fields based on first feature in collection
        // This assumes that all features have the same set of properties, which I think is always the case for shapefiles
        SimpleFeatureIterator it = featureCollection.features();
        SimpleFeature protoFt = it.next();
        if (propertyFields == null) {
            propertyFields = new ArrayList<String>();
            // No property fields specified, so use all property fields
            for (Property p : protoFt.getProperties()) {
                propertyFields.add(p.getName().toString());
            }
            // If ID field is specified, don't use it as a property
            if (originIDField != null && propertyFields.contains(originIDField)) {
                propertyFields.remove(originIDField);
            }
        }

        // Reset iterator
        it = featureCollection.features();

        FreeFormPointSet ret = new FreeFormPointSet(featureCollection.size());
        int i=0;
        while (it.hasNext()) {
            SimpleFeature feature = it.next();
            Geometry geom = (Geometry) feature.getDefaultGeometry();

            PointFeature ft = new PointFeature();
            ft.setGeom(geom);

            // Set feature's ID to the specified ID field, or to index if none is specified
            if (originIDField == null) {
                ft.setId(Integer.toString(i));
            } else {
                ft.setId(feature.getProperty(originIDField).getValue().toString());
            }

            for(Property prop : feature.getProperties() ){
                String propName = prop.getName().toString();
                if (propertyFields.contains(propName)) {
                    Object binding = prop.getType().getBinding();

                    //attempt to coerce the prop's value into an integer
                    int val;
                    if(binding.equals(Integer.class)){
                        val = (Integer)prop.getValue();
                    } else if(binding.equals(Long.class)){
                        val = ((Long)prop.getValue()).intValue();
                    } else if(binding.equals(String.class)){
                        try{
                            val = Integer.parseInt((String)prop.getValue());
                        } catch (NumberFormatException ex ){
                            continue;
                        }
                    } else {
                        LOG.debug("Property {} of feature {} could not be interpreted as int, skipping", prop.getName().toString(), ft.getId());
                        continue;
                    }

                    ft.addAttribute(propName, val);
                } else {
                    LOG.debug("Property {} not requested; ignoring", propName);
                }

            }

            ret.addFeature(ft, i);

            i++;
        }

        ArrayList<String> IDlist = new ArrayList<String>();
        for (String id : ret.ids) {
            IDlist.add(id);
        }
        LOG.debug("Created FreeFormPointSet from shapefile with IDs {}", IDlist);
        return ret;
    }

    // Not yet re-implemented
    public static FreeFormPointSet fromGeoJson(File filename) {
        try {
            FileInputStream fis = new FileInputStream(filename);
            int n = validateGeoJson(fis);
            if (n < 0)
                return null;
            fis.getChannel().position(0); // rewind file
            return fromValidatedGeoJson(fis, n);
        } catch (FileNotFoundException ex) {
            LOG.error("GeoJSON file not found: {}", filename);
            return null;
        } catch (IOException ex) {
            LOG.error("I/O exception while reading GeoJSON file: {}", filename);
            return null;
        }
    }

    /**
     * Examines a JSON stream to see if it matches the expected OTPA format.
     * TODO improve the level of detail of validation. Many files pass the validation and then crash the load function.
     *
     * @return the number of features in the collection if it's valid, or -1 if
     *         it doesn't fit the OTPA format.
     */
    private static int validateGeoJson(InputStream is) {
        int n = 0;
        JsonFactory f = new JsonFactory();
        try {
            JsonParser jp = f.createParser(is);
            JsonToken current = jp.nextToken();
            if (current != JsonToken.START_OBJECT) {
                LOG.error("Root of OTPA GeoJSON should be a JSON object.");
                return -1;
            }
            // Iterate over the key:value pairs in the top-level JSON object
            while (jp.nextToken() != JsonToken.END_OBJECT) {
                String key = jp.getCurrentName();
                current = jp.nextToken();
                if (key.equals("features")) {
                    if (current != JsonToken.START_ARRAY) {
                        LOG.error("Error: GeoJSON features are not in an array.");
                        return -1;
                    }
                    // Iterate over the features in the array
                    while (jp.nextToken() != JsonToken.END_ARRAY) {
                        n += 1;
                        jp.skipChildren();
                    }
                } else {
                    jp.skipChildren(); // ignore all other keys except features
                }
            }
            if (n == 0)
                return -1; // JSON has no features
            return n;
        } catch (Exception ex) {
            LOG.error("Exception while validating GeoJSON: {}", ex);
            return -1;
        }
    }

    /**
     * Reads with a combination of streaming and tree-model to allow very large
     * GeoJSON files. The JSON should be already validated, and you must pass in
     * the maximum number of features from that validation step.
     */
    private static FreeFormPointSet fromValidatedGeoJson(InputStream is, int n) {
        JsonFactory f = new MappingJsonFactory();
        FreeFormPointSet ret = new FreeFormPointSet(n);
        int index = 0;
        try {
            JsonParser jp = f.createParser(is);
            JsonToken current = jp.nextToken();
            // Iterate over the key:value pairs in the top-level JSON object
            while (jp.nextToken() != JsonToken.END_OBJECT) {
                String key = jp.getCurrentName();
                current = jp.nextToken();
                if (key.equals("properties")) {
                    JsonNode properties = jp.readValueAsTree();

                    if(properties.get("id") != null)
                        ret.id = properties.get("id").asText();
                    if(properties.get("label") != null)
                        ret.label = properties.get("label").asText();
                    if(properties.get("description") != null)
                        ret.label = properties.get("description").asText();
                }
                if (key.equals("features")) {
                    while (jp.nextToken() != JsonToken.END_ARRAY) {
                        // Read the feature into a tree model, which moves
                        // parser to its end.
                        JsonNode feature = jp.readValueAsTree();
                        ret.addFeature(feature, index++);
                    }
                } else {
                    jp.skipChildren(); // ignore all other keys except features
                }
            }
        } catch (Exception ex) {
            LOG.error("GeoJSON parsing failure", ex);
            return null;
        }
        return ret;
    }

    /**
     * Add one GeoJSON feature to this FreeFormPointSet from a Jackson node tree.
     * com.bedatadriven.geojson only exposed its streaming Geometry parser as a
     * public method. I made its tree parser public as well. Geotools also has a
     * GeoJSON parser called GeometryJson (which OTP wraps in
     * GeoJsonDeserializer) but it consumes straight text, not a Jackson model
     * or streaming parser.
     */
    private void addFeature(JsonNode feature, int index) {

        PointFeature feat = null;
        try {
            feat = PointFeature.fromJsonNode(feature);
        } catch (UnsupportedGeometryException e) {
            LOG.warn(e.message);
            return;
        }

        if (feat == null) {
            return;
        }

        addFeature(feat, index);
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

    /**
     * Add a single feature with a variable number of free-form properties.
     * Attribute data contains id value pairs, ids are in form "cat_id:prop_id".
     * If the properties and categories do not exist, they will be created.
     * TODO: read explicit schema or infer it and validate property presence as
     * they're read
     *
     * @param feat must be a Point, a Polygon, or a single-element MultiPolygon
     */
    public void addFeature(PointFeature feat, int index) {
        if (index >= capacity) {
            throw new AssertionError("Number of features seems to have grown since validation.");
        }

        polygons[index] = feat.getPolygon();
        lats[index] = feat.getLat();
        lons[index] = feat.getLon();

        ids[index] = feat.getId();

        for (Entry<String,Integer> ad : feat.getProperties().entrySet()) {
            String propId = ad.getKey();
            Integer propVal = ad.getValue();
            this.createPropertyForId(propId);
            this.properties.get(propId)[index] = propVal;
        }
    }

    public PointFeature getFeature(int index) {
        PointFeature ret = new PointFeature(ids[index]);

        if (polygons[index] != null) {
            try {
                ret.setGeom(polygons[index]);
            } catch (Exception e) {
                // The polygon is known to be clean; this should never happen. We
                // could pass the exception up but that'd just make the calling
                // function deal with an exception that will never pop. So
                // we'll make the compiler happy by catching it here silently.
            }
        }

        // ret.setGeom, if it was called, will already set the lat and lon
        // properties. But since every item in this pointset is guaranteed
        // to have a lat/lon coordinate, we defer to it as more authoritative.
        ret.setLat(lats[index]);
        ret.setLon(lons[index]);

        for (Entry<String, int[]> property : this.properties.entrySet()) {
            ret.addAttribute( property.getKey(), property.getValue()[index]);
        }

        return ret;
    }

    /**
     * Creates property, returning false if the property already exists.
     */
    public boolean createPropertyForId(String id) {

        if (properties.containsKey(id)) {
            return false;
        } else {
            properties.put(id, new int[capacity]);
            return true;
        }
    }

    public void writeJson(OutputStream out) {
        writeJson(out, false);
    }

    /**
     * Use the Jackson streaming API to output this as GeoJSON without creating
     * another object. The Indicator is a column store, and is transposed WRT
     * the JSON representation. Not yet re-implemented
     */
    public void writeJson(OutputStream out, Boolean forcePoints) {
        try {
            JsonFactory jsonFactory = new JsonFactory(); // ObjectMapper.getJsonFactory() is better
            JsonGenerator jgen = jsonFactory.createGenerator(out);
            jgen.setCodec(new ObjectMapper());
            jgen.writeStartObject();
            {

                jgen.writeStringField("type", "FeatureCollection");

                writeJsonProperties(jgen);

                jgen.writeArrayFieldStart("features");
                {
                    for (int f = 0; f < capacity; f++) {
                        writeFeature(f, jgen, forcePoints);
                    }
                }
                jgen.writeEndArray();
            }
            jgen.writeEndObject();
            jgen.close();
        } catch (IOException ioex) {
            LOG.info("IOException, connection may have been closed while streaming JSON.");
        }
    }

    // Not yet re-implemented. Could be useful as an alternative to writing verbose CSV travel time matrices
    public void writeJsonWithTimes(OutputStream out, String[] destinationIds, int[] times) {
        try {
            JsonFactory jsonFactory = new JsonFactory(); // ObjectMapper.getJsonFactory() is better
            JsonGenerator jgen = jsonFactory.createGenerator(out);
            jgen.setCodec(new ObjectMapper());
            jgen.writeStartObject();
            {

                jgen.writeStringField("type", "FeatureCollection");

                writeJsonProperties(jgen);

                jgen.writeArrayFieldStart("features");
                {
                    for (int f = 0; f < capacity; f++) {
                        writeFeature(f, jgen, true);
                    }
                }
                jgen.writeEndArray();
            }
            jgen.writeEndObject();
            jgen.close();
        } catch (IOException ioex) {
            LOG.info("IOException, connection may have been closed while streaming JSON.");
        }
    }

    private void writeJsonProperties(JsonGenerator jgen) throws IOException {
        jgen.writeObjectFieldStart("properties");
        {

            if (id != null)
                jgen.writeStringField("id", id);
            if (label != null)
                jgen.writeStringField("label", label);
            if (description != null)
                jgen.writeStringField("description", description);

            jgen.writeEndObject();
        }
        jgen.writeEndObject();

    }

    /**
     * Pairs an array of times with the array of features in this pointset,
     * writing out the resulting (ID,time) pairs to a JSON object.
     */
    protected void writeTimes(JsonGenerator jgen, int[] times) throws IOException {
        jgen.writeObjectFieldStart("times");
        for (int i = 0; i < times.length; i++) { // capacity is now 1 if this is
            // a one-to-many indicator
            int t = times[i];
            if (t != FastRaptorWorker.UNREACHED)
                jgen.writeNumberField(ids[i], t);
        }
        jgen.writeEndObject();
    }

    /**
     * This writes either a polygon or lat/lon point defining the feature. In
     * the case of polygons, we convert these back to centroids on import, as
     * OTPA depends on the actual point. The polygons are kept for derivative
     * uses (e.g. visualization)
     *
     * @param i
     *            the feature index
     * @param jgen
     *            the Jackson streaming JSON generator to which the geometry
     *            will be written
     * @throws IOException
     */
    private void writeFeature(int i, JsonGenerator jgen, Boolean forcePoints) throws IOException {

        ObjectMapper geomSerializer = new ObjectMapper();

        jgen.writeStartObject();
        {
            jgen.writeStringField("id", ids[i]);
            jgen.writeStringField("type", "Feature");
            jgen.writeFieldName("geometry");
            {

                if (!forcePoints && polygons != null && polygons.length >= i && polygons[i] != null) {
                    org.geojson.Polygon p = new org.geojson.Polygon();
                    List<LngLatAlt> shell = new ArrayList<LngLatAlt>();
                    for (Coordinate c : polygons[i].getExteriorRing().getCoordinates()) {
                        shell.add(new LngLatAlt(c.x, c.y));
                    }
                    p.add(shell);
                    geomSerializer.writeValue(jgen, p);
                } else {
                    org.geojson.Point p = new org.geojson.Point(lons[i], lats[i]);
                    geomSerializer.writeValue(jgen, p);
                }

            }
            jgen.writeObjectFieldStart("properties");
            {
                writeStructured(i, jgen);
            }
            jgen.writeEndObject();
        }
        jgen.writeEndObject();
    }

    /**
     * This will be called once per point in an origin/destination pointset, and
     * once per origin in a one- or many-to-many indicator.
     */
    protected void writeStructured(int i, JsonGenerator jgen) throws IOException {
        jgen.writeObjectFieldStart("structured");
        for (Entry<String,int[]> entry : properties.entrySet()) {
            jgen.writeNumberField( entry.getKey(), entry.getValue()[i] );
        }
        jgen.writeEndObject();
    }

    /**
     * Get a subset of this point set containing only the specified point IDs.
     */
    private FreeFormPointSet slice(List<String> ids) {

        FreeFormPointSet ret = new FreeFormPointSet(ids.size());

        HashSet<String> idsHashSet = new HashSet<String>(ids);

        ret.id = id;
        ret.label = label;
        ret.description = description;

        int n = 0;

        for (int i = 0; i < this.ids.length; i++) {
            if(idsHashSet.contains(this.ids[i])) {
                ret.lats[n] = this.lats[i];
                ret.lons[n] = this.lons[i];
                ret.ids[n] = this.ids[i];
                ret.polygons[n] = this.polygons[i];
                n++;
            }
        }

        return ret;
    }

    private FreeFormPointSet slice(int start, int end) {
        FreeFormPointSet ret = new FreeFormPointSet(end - start);

        ret.id = id;
        ret.label = label;
        ret.description = description;

        int n = 0;
        for (int i = start; i < end; i++) {
            ret.lats[n] = this.lats[i];
            ret.lons[n] = this.lons[i];
            ret.ids[n] = this.ids[i];
            ret.polygons[n] = this.polygons[i];
            n++;
        }

        for(Entry<String, int[]> property : this.properties.entrySet()) {
            int[] data = property.getValue();

            int[] magSlice = new int[end-start];
            n=0;
            for(int i=start; i<end; i++){
                magSlice[n] = data[i];
                n++;
            }

            ret.properties.put( property.getKey(), magSlice );
        }

        return ret;
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


}
