package org.opentripplanner.analyst;

import junit.framework.TestCase;

import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opentripplanner.analyst.EmptyPolygonException;
import org.opentripplanner.analyst.PointFeature;
import org.opentripplanner.analyst.PointSet;
import org.opentripplanner.analyst.UnsupportedGeometryException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class PointSetTest extends TestCase {

    public void testPointSets() throws IOException {
        PointSet austin = PointSet.fromCsv(new File("src/test/resources/pointset/austin.csv"));
        assertNotNull(austin);
        assertEquals(austin.capacity, 15922);
        
        assertEquals(-1, austin.getIndexForFeature("1"));
    }

    /** Factory method should return null but not throw an exception on malformed CSV. */
    public void testBogusCSV() throws IOException {
        PointSet points = PointSet.fromCsv(new File("src/test/resources/pointset/bogus.csv"));
        assertNull(points);
    }

    public void testLoadGeoJson() {
        PointSet points = PointSet.fromGeoJson(new File("src/test/resources/pointset/population.geo.json"));
        assertNotNull(points);
        assertEquals(points.capacity, 2);
        
        assertEquals(1, points.getIndexForFeature(points.ids[1]));
        assertEquals(0, points.getIndexForFeature(points.ids[0]));
        assertEquals(-1, points.getIndexForFeature("THIS FEATURE DOES NOT EXIST."));
    }
    
    public void testLoadShapefile() throws NoSuchAuthorityCodeException, IOException, FactoryException, EmptyPolygonException, UnsupportedGeometryException {
        PointSet points = PointSet.fromShapefile(new File("src/test/resources/pointset/shp/austin.shp"));
        assertNotNull(points);
        PointFeature ft = points.getFeature(0);
        int pop = ft.getProperty("DEC_10_S_2");
        assertEquals( pop, 42 );
    }
    
    public void testGetFeature() {
        PointSet points = PointSet.fromGeoJson(new File("src/test/resources/pointset/population.geo.json"));
        PointFeature pt = points.getFeature(0);
        
        assertNotNull(pt);
        assertEquals( pt.getId(), "XYZ0001");
        Map<String,Integer> attrs = pt.getProperties();
        assertEquals( attrs.size(), 2 );
        assertEquals( pt.getProperty( "age" ), 10 );
    }
    
    public void testSlice() {
    	PointSet points = PointSet.fromGeoJson(new File("src/test/resources/pointset/population.geo.json"));
    	
    	PointSet all = points.slice(0, points.featureCount());
    	assertEquals( all.featureCount(), points.featureCount() );
    	
    	PointSet firstHalf = points.slice(0, 1);
    	assertEquals( firstHalf.featureCount(), 1 );
    	assertEquals( firstHalf.getFeature(0).getId(), "XYZ0001" );
    	
    	PointSet lastHalf = points.slice(1, 2);
    	assertEquals( lastHalf.featureCount(), 1 );
    	assertEquals( lastHalf.getFeature(0).getId(), "XYZ0002" );
    }

    /* TODO Round trip serialization and deserialization to GeoJSON. */

}
