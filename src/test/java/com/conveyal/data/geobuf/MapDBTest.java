package com.conveyal.data.geobuf;

import com.google.common.io.Files;
import junit.framework.TestCase;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.conveyal.data.geobuf.IntegrationTest.ringsEqual;

/**
 * Test the MapDB Geobuf serializer.
 */
public class MapDBTest extends TestCase {
    private final GeometryFactory gf = new GeometryFactory();

    /** make sure geometry is serialized and deserialized correctly */
    @Test
    public void testMapdb () throws Exception {
        // this is a complex, purpose-built shape with holes and nested holes
        WKTReader rdr = new WKTReader();
        Geometry geom =
                rdr.read("MULTIPOLYGON (((5.5096587473586 52.791746641422968,5.561982765385025 52.796145563292448,5.601341540006672 52.776234653777969,5.600878495599358 52.760259621725652,5.596711095933537 52.750535689172068,5.574716486586146 52.735023701527062,5.545081644518082 52.734329134916095,5.505028303285465 52.746599811709906,5.487201093603895 52.76975203207558,5.484422827160015 52.789894463793715,5.49183153767703 52.797766218718046,5.501555470230613 52.79568251888513,5.501555470230613 52.79568251888513,5.512900058209794 52.793830341255877,5.5096587473586 52.791746641422968),(5.498082637175762 52.78225423107304,5.531190312298677 52.782948797684014,5.527949001447483 52.774613998352372,5.510584836173227 52.774613998352372,5.498082637175762 52.78225423107304),(5.566150165050846 52.765121588002444,5.551332744016815 52.776929220388936,5.542766422481515 52.770446598686547,5.557120799108233 52.752619389004977,5.566150165050846 52.765121588002444),(5.530958790095021 52.759333532911022,5.535589234168156 52.790125985997371,5.548554477572933 52.790820552608345,5.534663145353528 52.755860699856171,5.530958790095021 52.759333532911022),(5.52887509026211 52.751693300190354,5.562677331995995 52.750072644764757,5.55804688792286 52.743126978655049,5.524013123985318 52.747525900524529,5.517530502282929 52.757249833078113,5.530727267891364 52.765816154613411,5.52887509026211 52.751693300190354)),((5.555268621478979 52.760259621725652,5.547165344350993 52.769288987668261,5.550869699609501 52.773456387334086,5.562214287588682 52.7653531102061,5.555268621478979 52.760259621725652),(5.553416443849725 52.767668332242664,5.559204498941144 52.765584632409755,5.555037099275323 52.763732454780502,5.550175132998531 52.768594421057294,5.551795788424128 52.770215076482891,5.553416443849725 52.767668332242664)))");

        // write it to mapdb
        GeobufFeature feat = new GeobufFeature();
        feat.geometry = geom;
        feat.properties = new HashMap<>();
        feat.properties.put("string", "Matt");
        feat.properties.put("int", 2015);
        feat.properties.put("double", 49.94);

        File directory = Files.createTempDir();
        File dbFile = new File(directory, "test.db");

        DB db = DBMaker.newFileDB(dbFile).make();
        Map<String, GeobufFeature> map = db.createTreeMap("map")
                .valueSerializer(new GeobufEncoder.GeobufFeatureSerializer(12))
                .make();
        map.put("feat", feat);
        db.commit();
        db.close();

        // re-open the db
        db = DBMaker.newFileDB(dbFile).make();
        map = db.getTreeMap("map");
        GeobufFeature feat2 = map.get("feat");
        db.close();

        // make sure the properties survived
        assertTrue(feat2.properties.get("string") instanceof String);
        assertEquals("Matt", feat2.properties.get("string"));

        // ints become longs
        assertTrue(feat2.properties.get("int") instanceof Long);
        assertEquals(2015, (long) feat2.properties.get("int"));

        assertTrue(feat2.properties.get("double") instanceof Double);
        assertEquals(49.94, (double) feat2.properties.get("double"), 1e-12);

        // test that the holes survived
        assertFalse(feat2.geometry.contains(gf.createPoint(new Coordinate(5.55498, 52.76564))));
        assertTrue(feat2.geometry.contains(gf.createPoint(new Coordinate(5.5559, 52.7627))));
        assertFalse(feat2.geometry.contains(gf.createPoint(new Coordinate(5.5567, 52.7575))));
        assertTrue(feat2.geometry.contains(gf.createPoint(new Coordinate(5.5146, 52.7654))));
        assertFalse(feat2.geometry.contains(gf.createPoint(new Coordinate(5.5248, 52.7560))));

        // make sure that the geometries are equal
        // I believe parts and rings should be in the same order, even though that's not strictly necessary
        assertEquals(feat.geometry.getNumGeometries(), feat2.geometry.getNumGeometries());

        for (int partIdx = 0; partIdx < feat.geometry.getNumGeometries(); partIdx++) {
            Polygon part = (Polygon) feat.geometry.getGeometryN(partIdx);
            Polygon part2 = (Polygon) feat2.geometry.getGeometryN(partIdx);

            assertEquals(part.getNumInteriorRing(), part2.getNumInteriorRing());

            ringsEqual(part.getExteriorRing(), part2.getExteriorRing());

            for (int ringIdx = 0; ringIdx < part.getNumInteriorRing(); ringIdx++) {
                ringsEqual(part.getInteriorRingN(ringIdx), part2.getInteriorRingN(ringIdx));
            }
        }
    }
}
