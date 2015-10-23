package com.conveyal.r5.analyst;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

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

    public ResultSet() {
    }

    private void buildIsochrones (int[] times, PointSet targets) {
        throw new UnsupportedOperationException("Isochrones not yet supported");
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
        this.histograms = Histogram.buildAll(times, targets);
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
    public void writeJson(OutputStream output, PointSet ps) {
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

    /** A set of result sets from profile routing: min, avg, max */;
    public static class RangeSet implements Serializable {
        public static final long serialVersionUID = 1L;

        public ResultSet min;
        public ResultSet avg;
        public ResultSet max;
    }
}
