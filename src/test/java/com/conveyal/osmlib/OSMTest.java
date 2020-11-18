package com.conveyal.osmlib;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mapdb.Fun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class OSMTest {
	@Test
    public void testOSM(){
		OSM osm = new OSM("./src/test/resources/tmp");
		osm.readFromFile("./src/test/resources/bangor_maine.osm.pbf");
		Assertions.assertEquals(osm.nodes.size(), 12030);
		Assertions.assertEquals(osm.ways.size(), 1828);
		Assertions.assertEquals(osm.relations.size(), 2);

		// make sure the indices work
		for (Map.Entry<Long, Relation> e : osm.relations.entrySet()) {
			Relation relation = e.getValue();
			long id = e.getKey();
			// Tested: Bangor contains relations with way, node, and relation members
			for (Relation.Member member : relation.members) {
				if (member.type == OSMEntity.Type.NODE)
					Assertions.assertTrue(osm.relationsByNode.contains(Fun.t2(member.id, id)));
				else if (member.type == OSMEntity.Type.WAY)
					Assertions.assertTrue(osm.relationsByWay.contains(Fun.t2(member.id, id)));
				else if (member.type == OSMEntity.Type.RELATION)
					Assertions.assertTrue(osm.relationsByRelation.contains(Fun.t2(member.id, id)));
			}
		}
	}
	
	@AfterEach
    public void tearDown() throws IOException{
		Files.delete( Paths.get("./src/test/resources/tmp") );
		Files.delete( Paths.get("./src/test/resources/tmp.p") );
	}
}
