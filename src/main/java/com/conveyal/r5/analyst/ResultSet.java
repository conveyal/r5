package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeoJsonFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.precision.GeometryPrecisionReducer;
import com.vividsolutions.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * This holds the results of a one-to-many search from a single origin point to a whole set of destination points.
 * This may be either a profile search (capturing variation over a time window of several hours) or a "classic" search
 * at a single departure time. Those results are expressed as histograms: bins representing how many destinations you
 * can reach after M minutes of travel. For large point sets (large numbers of origins and destinations), this is
 * significantly more compact than a full origin-destination travel time matrix. It makes the total size of the results
 * linear in the number of origins, rather than quadratic.
 *
 * Optionally, this can also carry travel times to every point in the target pointset and/or a series vector
 * isochrones around the origin point.
 */
public class ResultSet implements Serializable{

    private static final long serialVersionUID = -6723127825189535112L;

    private static final Logger LOG = LoggerFactory.getLogger(ResultSet.class);

    /** An identifier consisting of the ids for the pointset and time surface that were combined. */
    public String id;

    /** One histogram for each category of destination points in the target pointset. */
    public Map<String,Histogram> histograms = new HashMap<>();
    // FIXME aren't the histogram.counts identical for all these histograms?

    /** Times to reach every feature, may be null */
    public int[] times;

    public IsochroneFeature[] isochrones;

    public ResultSet() {
    }

    private void buildIsochrones (int[] times, PointSet targets) {
        if (!WebMercatorGridPointSet.class.isInstance(targets))
            this.isochrones = null;
        else {
            this.isochrones = new IsochroneFeature[24];
            for (int cutoff = 5 * 60, i = 0; cutoff <= 120 * 60; cutoff += 5 * 60) {
                this.isochrones[i++] = new IsochroneFeature(cutoff, (WebMercatorGridPointSet) targets, times);
            }
        }
    }
    
    /** Build a new ResultSet directly from times at point features, optionally including histograms or interpolating isochrones */
    public ResultSet(int[] times, PointSet targets, boolean includeTimes, boolean includeHistograms, boolean includeIsochrones) {
        if (includeTimes)
            this.times = times;

        if (includeHistograms)
            buildHistograms(times, targets);

        if (includeIsochrones)
            buildIsochrones(times, targets);
    }

    /** 
     * Given an array of travel times to reach each point in the supplied pointset, make a histogram of 
     * travel times to reach each separate category of points (i.e. "property") within the pointset.
     * Each new histogram object will be stored as a part of this result set keyed on its property/category.
     */
    protected void buildHistograms(int[] times, PointSet targets) {
        if (FreeFormPointSet.class.isInstance(targets))
            this.histograms = Histogram.buildAll(times, (FreeFormPointSet) targets);
        else
            this.histograms = null; // gridded pointsets don't have indicator values
    }

    /**
     * Sum the values of specified categories at all time limits within the
     * bounds of the search. If no categories are specified, sum all categories. 
     */
    public long sum (String... categories) {
        return sum((Integer) null, categories);
    }
    
    /**
     * Sum the values of the specified categories up to the time limit specified
     * (in seconds). If no categories are specified, sum all categories.
     */
    public long sum(Integer timeLimit, String... categories) {
        
        if (categories.length == 0)
            categories = histograms.keySet().toArray(new String[histograms.keySet().size()]);

        long value = 0l;

        int maxMinutes;

        if(timeLimit != null)
            maxMinutes = timeLimit / 60;
        else
            maxMinutes = Integer.MAX_VALUE;

        for(String k : categories) {
            int minute = 0;
            for(int v : histograms.get(k).sums) {
                if(minute < maxMinutes)
                    value += v;
                minute++;
            }
        }

        return value;
    }

    /**
     * Serialize this ResultSet to the given output stream as a JSON document, when the pointset is not available.
     * TODO: explain why and when that would happen 
     */
    public void writeJson(OutputStream output) {
        writeJson(output, null);
    }

    /** 
     * Serialize this ResultSet to the given output stream as a JSON document.
     * properties: a list of the names of all the pointSet properties for which we have histograms.
     * data: for each property, a histogram of arrival times.
     */
    public void writeJson(OutputStream output, FreeFormPointSet ps) {
        try {
            JsonFactory jsonFactory = new JsonFactory(); 

            JsonGenerator jgen = jsonFactory.createGenerator(output);
            jgen.setCodec(new ObjectMapper());

            jgen.writeStartObject(); {	

                if(ps == null) {
                    jgen.writeObjectFieldStart("properties"); {
                        if (id != null)
                            jgen.writeStringField("id", id);
                    }
                    jgen.writeEndObject();
                }
                else {
                    ps.writeJsonProperties(jgen);
                }

                jgen.writeObjectFieldStart("data"); {
                    for(String propertyId : histograms.keySet()) {

                        jgen.writeObjectFieldStart(propertyId); {
                            histograms.get(propertyId).writeJson(jgen);
                        }
                        jgen.writeEndObject();

                    }
                }
                jgen.writeEndObject();
            }
            jgen.writeEndObject();

            jgen.close();
        } catch (IOException ioex) {
            LOG.info("IOException, connection may have been closed while streaming JSON.");
        }
    }

    public void writeIsochrones (JsonGenerator jgen) throws IOException {
        jgen.writeStringField("type", "FeatureCollection");
        jgen.writeArrayFieldStart("features");

        for (IsochroneFeature feature : this.isochrones) {
            jgen.writeStartObject();

            jgen.writeObjectFieldStart("properties");
            jgen.writeNumberField("time", feature.cutoffSec);
            jgen.writeEndObject();

            jgen.writeObjectFieldStart("geometry");
            jgen.writeStringField("type", "MultiPolygon");

            jgen.writeArrayFieldStart("coordinates");
            // TODO there has to be a better way to do this
            for (int gidx = 0; gidx < feature.geometry.getNumGeometries(); gidx++) {
                Polygon polygon = (Polygon) feature.geometry.getGeometryN(gidx);

                jgen.writeStartArray();

                // write outer ring
                jgen.writeStartArray();

                for (Coordinate coord : polygon.getExteriorRing().getCoordinates()) {
                    jgen.writeStartArray();
                    jgen.writeNumber(coord.x);
                    jgen.writeNumber(coord.y);
                    jgen.writeEndArray();
                }

                jgen.writeEndArray();

                for (int ridx = 0; ridx < polygon.getNumInteriorRing(); ridx++) {
                    jgen.writeStartArray();

                    for (Coordinate coord : polygon.getInteriorRingN(ridx).getCoordinates()) {
                        jgen.writeStartArray();
                        jgen.writeNumber(coord.x);
                        jgen.writeNumber(coord.y);
                        jgen.writeEndArray();
                    }

                    jgen.writeEndArray();
                }

                jgen.writeEndArray();
            }

            jgen.writeEndArray(); // coords
            jgen.writeEndObject(); // geometry
            jgen.writeEndObject(); // feature
        }

        jgen.writeEndArray();
    }

    /**
     * Reduces and simplifies (using DouglasPeuckerSimplifier) the inputted geometry.
     *
     * @param geometry  The geometry to modify
     * @return          The new geometry that is simplified and then reduced
     */
    private Geometry reduceAndSimplifyGeometry(Geometry geometry) {
        PrecisionModel precisionModel = new PrecisionModel(10000);
        GeometryPrecisionReducer precisionReducer = new GeometryPrecisionReducer(precisionModel);
        return precisionReducer.reduce(DouglasPeuckerSimplifier.simplify(geometry, .001));
    }

    /**
     * Write GeoJSON features of the resulting isochrone
     *
     * @param returnDistinctAreas   if true, diff each polygon to obtain non-overlapping areas for each isochrone time-range.
     * @return                      A list of GeoJSON features (multi-polygons).
     */
    public List<GeoJsonFeature> generateGeoJSONFeatures(boolean returnDistinctAreas) {
        List<GeoJsonFeature> features = new ArrayList<>();
        int calcStartTime = (int) System.currentTimeMillis();
        Geometry traversedIsochrone = null;

        for (IsochroneFeature isochroneFeature : this.isochrones) {
            Geometry isochroneGeometry;
            if (returnDistinctAreas) {
                // reduce geometry precision and buffer by 0 to avoid TopologyExceptions
                Geometry curFeatureGeometry = reduceAndSimplifyGeometry(isochroneFeature.geometry);

                if (traversedIsochrone == null) {
                    isochroneGeometry = curFeatureGeometry;
                } else {
                    isochroneGeometry = curFeatureGeometry.difference(reduceAndSimplifyGeometry(traversedIsochrone));
                }
            } else {
                isochroneGeometry = isochroneFeature.geometry;
            }
            GeoJsonFeature feature = new GeoJsonFeature(isochroneGeometry);
            feature.addProperty("time", isochroneFeature.cutoffSec);
            features.add(feature);
            traversedIsochrone = isochroneFeature.geometry;
        }

        LOG.info("isochrone > geojson conversion finished in {} seconds", (System.currentTimeMillis() - calcStartTime) / 1000.0);
        return features;
    }

    /** A set of result sets from profile routing: min, avg, max */;
    public static class RangeSet implements Serializable {
        public static final long serialVersionUID = 1L;

        public ResultSet min;
        public ResultSet avg;
        public ResultSet max;
    }
}
