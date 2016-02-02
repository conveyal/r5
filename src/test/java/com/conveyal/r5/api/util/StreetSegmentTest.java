package com.conveyal.r5.api.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Created by mabu on 2.2.2016.
 */
public class StreetSegmentTest {
    private static final Logger LOG = LoggerFactory.getLogger(StreetSegmentTest.class);
    StreetSegment streetSegment;

    @Before
    public void setUp() throws Exception {
        GeometryWKTDeserializer geometryWKTDeserializer = new GeometryWKTDeserializer();

        SimpleModule module = new SimpleModule("GeometryWKTDeserializerModule",
            new Version(1,0,0, null));
        module.addDeserializer(LineString.class, geometryWKTDeserializer);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        InputStream file = getClass().getResourceAsStream("streetSegmentWALK.json");
        streetSegment = mapper.readValue(file, StreetSegment.class);
    }

    @Test
    public void testCompact() throws Exception {
        Assert.assertNotNull(streetSegment);
        Assert.assertEquals(24, streetSegment.streetEdges.size());
        /*LOG.info("BEFORE:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        streetSegment.compactEdges();
        /*LOG.info("AFTER:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        Assert.assertEquals(20, streetSegment.streetEdges.size());


    }
}

class GeometryWKTDeserializer extends JsonDeserializer<LineString>
{
    static WKTReader wktReader = new WKTReader();
    /**
     * Method that can be called to ask implementation to deserialize
     * JSON content into the value type this serializer handles.
     * Returned instance is to be constructed by method itself.
     * <p>
     * Pre-condition for this method is that the parser points to the
     * first event that is part of value to deserializer (and which
     * is never JSON 'null' literal, more on this below): for simple
     * types it may be the only value; and for structured types the
     * Object start marker or a FIELD_NAME.
     * </p>
     * <p>
     * The two possible input conditions for structured types result
     * from polymorphism via fields. In the ordinary case, Jackson
     * calls this method when it has encountered an OBJECT_START,
     * and the method implementation must advance to the next token to
     * see the first field name. If the application configures
     * polymorphism via a field, then the object looks like the following.
     * <pre>
     *      {
     *          "@class": "class name",
     *          ...
     *      }
     *  </pre>
     * Jackson consumes the two tokens (the <tt>@class</tt> field name
     * and its value) in order to learn the class and select the deserializer.
     * Thus, the stream is pointing to the FIELD_NAME for the first field
     * after the @class. Thus, if you want your method to work correctly
     * both with and without polymorphism, you must begin your method with:
     * <pre>
     *       if (jp.getCurrentToken() == JsonToken.START_OBJECT) {
     *         jp.nextToken();
     *       }
     *  </pre>
     * This results in the stream pointing to the field name, so that
     * the two conditions align.
     * </p>
     * <p>
     * Post-condition is that the parser will point to the last
     * event that is part of deserialized value (or in case deserialization
     * fails, event that was not recognized or usable, which may be
     * the same event as the one it pointed to upon call).
     * <p>
     * Note that this method is never called for JSON null literal,
     * and thus deserializers need (and should) not check for it.
     *
     * @param p    Parsed used for reading JSON content
     * @param ctxt Context that can be used to access information about
     *             this deserialization activity.
     * @return Deserialized value
     */
    @Override
    public LineString deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
        String value = p.getValueAsString();
        try {
            return (LineString) wktReader.read(value);
        } catch (ParseException e) {
            e.printStackTrace();
            return null;
        }

    }
}