package com.conveyal.osmlib;

import junit.framework.TestCase;
import org.mapdb.Fun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class OSMTest extends TestCase {
	public void testOSM(){
		OSM osm = new OSM("./src/test/resources/tmp");
		osm.readFromFile("./src/test/resources/bangor_maine.osm.pbf");
		assertEquals( osm.nodes.size(), 12030 );
		assertEquals( osm.ways.size(), 1828 );
		assertEquals( osm.relations.size(), 2 );

		// make sure the indices work
		for (Map.Entry<Long, Relation> e : osm.relations.entrySet()) {
			Relation relation = e.getValue();
			long id = e.getKey();
			// Tested: Bangor contains relations with way, node, and relation members
			for (Relation.Member member : relation.members) {
				if (member.type == OSMEntity.Type.NODE)
					assertTrue(osm.relationsByNode.contains(Fun.t2(member.id, id)));
				else if (member.type == OSMEntity.Type.WAY)
					assertTrue(osm.relationsByWay.contains(Fun.t2(member.id, id)));
				else if (member.type == OSMEntity.Type.RELATION)
					assertTrue(osm.relationsByRelation.contains(Fun.t2(member.id, id)));
			}
		}
	}
	
	public void tearDown() throws IOException{
		Files.delete( Paths.get("./src/test/resources/tmp") );
		Files.delete( Paths.get("./src/test/resources/tmp.p") );
	}
}
