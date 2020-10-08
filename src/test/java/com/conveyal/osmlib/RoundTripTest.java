package com.conveyal.osmlib;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;

public class RoundTripTest extends TestCase {

    static final String TEST_FILE = "./src/test/resources/porto_portugal.osm.pbf";

    public void testVexFile() throws Exception {

        // Load OSM data from PBF
        OSM osmOriginal = new OSM(null);
        osmOriginal.readFromFile(TEST_FILE);
        assertTrue(osmOriginal.nodes.size() > 1);
        assertTrue(osmOriginal.ways.size() > 1);
        assertTrue(osmOriginal.relations.size() > 1);

        // Write OSM data out to a VEX file
        File vexFile = File.createTempFile("test", ".vex");
        osmOriginal.writeToFile(vexFile.getPath());

        // Read OSM data back in from VEX file
        OSM osmCopy = new OSM(null);
        osmCopy.readFromFile(vexFile.getPath());

        // Compare PBF data to VEX data
        compareOsm(osmOriginal, osmCopy);

    }

    public void testPbfFile() throws Exception {

        // Load OSM data from PBF
        OSM osmOriginal = new OSM(null);
        osmOriginal.readFromFile(TEST_FILE);
        assertTrue(osmOriginal.nodes.size() > 1);
        assertTrue(osmOriginal.ways.size() > 1);
        assertTrue(osmOriginal.relations.size() > 1);

        // Write OSM data out to a PBF file
        File ourPbfFile = File.createTempFile("test", ".osm.pbf");
        osmOriginal.writeToFile(ourPbfFile.getPath());

        // Read OSM data back in from VEX file
        OSM osmCopy = new OSM(null);
        osmCopy.readFromFile(ourPbfFile.getPath());

        // Compare PBF data to VEX data
        compareOsm(osmOriginal, osmCopy);

    }

    public void testVexStream() throws Exception {

        // Create an input/output pipe pair so we can read in a VEX stream without using a file
        final PipedOutputStream outStream = new PipedOutputStream();
        final PipedInputStream inStream = new PipedInputStream(outStream);

        // Create a separate thread that will read a PBF file and convert it directly into a VEX stream
        new Thread(
            new Runnable() {
                public void run() {
                    try {
                        OSMEntitySink vexSink = new VexOutput(outStream);
                        FileInputStream pbfFileInputStream = new FileInputStream(TEST_FILE);
                        OSMEntitySource pbfSource = new PBFInput(pbfFileInputStream);
                        pbfSource.copyTo(vexSink);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        ).start();

        // Stream VEX data in from the thread and put it in a MapDB
        OSM osmCopy = new OSM(null);
        osmCopy.readVex(inStream);

        // Load up the original PBF file for comparison
        OSM osmOriginal = new OSM(null);
        osmOriginal.readFromFile(TEST_FILE);

        // Compare PBF data to VEX stream
        compareOsm(osmOriginal, osmCopy);

    }

    private void compareOsm (OSM original, OSM copy) {
        System.out.println("Checking that OSM data is identical after round-trip...");
        compareMap(original.nodes, copy.nodes);
        compareMap(original.ways, copy.ways);
        compareMap(original.relations, copy.relations);
    }

    private <K,V> void compareMap (Map<K,V> m1, Map<K,V> m2) {
        assertEquals(m1.size(), m2.size());
        for (Map.Entry<K,V> entry : m1.entrySet()) {
            V e1 = entry.getValue();
            V e2 = m2.get(entry.getKey());
            // System.out.println(e1);
            // System.out.println(e2);
            assertEquals(e1, e2);
        }
    }

}