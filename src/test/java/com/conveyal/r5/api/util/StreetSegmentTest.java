package com.conveyal.r5.api.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by mabu on 2.2.2016.
 */
public class StreetSegmentTest {
    private static final Logger LOG = LoggerFactory.getLogger(StreetSegmentTest.class);

    private static ObjectMapper mapper;

    private StreetSegment loadFile(String filename) throws IOException {
        InputStream file = getClass().getResourceAsStream(filename);
        return mapper.readValue(file, StreetSegment.class);
    }

    @BeforeAll
    public static void setUpClass() throws Exception {
        GeometryWKTDeserializer geometryWKTDeserializer = new GeometryWKTDeserializer();

        SimpleModule module = new SimpleModule("GeometryWKTDeserializerModule",
            new Version(1,0,0, null));
        module.addDeserializer(LineString.class, geometryWKTDeserializer);
        mapper = new ObjectMapper();
        mapper.registerModule(module);

    }

    //Tests streetSegment which has 3 edges which are similar then 2 different edges
    @Test
    public void testCompactSameDiff() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentWALK.json");

        //Gets part of 3 similar and 2 different edges
        streetSegment.streetEdges = streetSegment.streetEdges.subList(3,9);
        assertNotNull(streetSegment);
        assertEquals(6, streetSegment.streetEdges.size());
        int distanceBefore = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        /*LOG.info("BEFORE:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        streetSegment.compactEdges();
        int distanceAfter = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        /*LOG.info("AFTER:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        assertEquals(3, streetSegment.streetEdges.size());
        assertEquals(distanceBefore, distanceAfter);


    }

    //Tests streetSegment which has different edges then 3 edges which are similar then different edges
    @Test
    public void testCompactDiffSameDiff() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentWALK.json");
        assertNotNull(streetSegment);
        assertEquals(24, streetSegment.streetEdges.size());
        int distanceBefore = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        /*LOG.info("BEFORE:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        streetSegment.compactEdges();
        int distanceAfter = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        /*LOG.info("AFTER:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        assertEquals(21, streetSegment.streetEdges.size());
        assertEquals(distanceBefore, distanceAfter);


    }

    //Tests streetSegment which has different edgen then 3 edges which are similar
    @Test
    public void testCompactDiffSame() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentWALK.json");

        //Gets part of different and similar edges
        streetSegment.streetEdges = streetSegment.streetEdges.subList(0,7);
        int distanceBefore = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        assertNotNull(streetSegment);
        assertEquals(7, streetSegment.streetEdges.size());
        /*LOG.info("BEFORE:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        streetSegment.compactEdges();
        int distanceAfter = streetSegment.streetEdges.stream().mapToInt(se -> se.distance).sum();
        /*LOG.info("AFTER:");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        assertEquals(4, streetSegment.streetEdges.size());
        assertEquals(distanceBefore, distanceAfter);


    }

    @Test
    public void testRoundabout() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentCAR_ROUNDABOUT.json");
        /*for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }*/
        assertEquals(RelativeDirection.CIRCLE_COUNTERCLOCKWISE, streetSegment.streetEdges.get(2).relativeDirection);
        assertEquals(RelativeDirection.CIRCLE_CLOCKWISE, streetSegment.streetEdges.get(3).relativeDirection);
        assertEquals(RelativeDirection.CIRCLE_CLOCKWISE, streetSegment.streetEdges.get(4).relativeDirection);
        streetSegment.compactEdges();
        assertEquals(RelativeDirection.CIRCLE_COUNTERCLOCKWISE, streetSegment.streetEdges.get(2).relativeDirection);
        assertEquals(RelativeDirection.RIGHT, streetSegment.streetEdges.get(3).relativeDirection);
    }

    @Disabled("Roundabout exit numbers and compactness isn't supported yet")
    @Test
    public void testRoundaboutExit() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentCAR_ROUNDABOUT.json");
        for (StreetEdgeInfo streetEdgeInfo: streetSegment.streetEdges) {
            LOG.info(streetEdgeInfo.toString());
        }

        //Compact roundabout and add exit number
        assertEquals(RelativeDirection.CIRCLE_COUNTERCLOCKWISE, streetSegment.streetEdges.get(2).relativeDirection);
        assertEquals("3", streetSegment.streetEdges.get(2).exit);
        assertEquals(RelativeDirection.RIGHT, streetSegment.streetEdges.get(3).relativeDirection);
    }

    @Test
    public void testSimilarTo() throws Exception {
        StreetSegment streetSegment = loadFile("streetSegmentCAR_ROUNDABOUT.json");

        assertFalse(streetSegment.streetEdges.get(0).similarTo(streetSegment.streetEdges.get(1)));
        assertFalse(streetSegment.streetEdges.get(1).similarTo(streetSegment.streetEdges.get(2)));
        assertTrue(streetSegment.streetEdges.get(2).similarTo(streetSegment.streetEdges.get(3)));
        assertTrue(streetSegment.streetEdges.get(3).similarTo(streetSegment.streetEdges.get(4)));

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
