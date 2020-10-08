package com.conveyal.osmlib;

import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** 
 * A sparse bit set capable of handling 64-bit int indexes (like OSM IDs).
 * Node numbers in OSM tend to be contiguous so maybe the blocks should be bigger.
 *
 * MapDB TreeSets are much faster than MapDB HashSets, but in-memory NodeTrackers are
 * much faster than MapDB TreeSets.
 *
 * To save space, this uses RoaringBitmaps. RoaringBitmaps currently only support 32 bit keys
 * so we use multiple RoaringBitmaps containing the low 32 bits, stored in a map from the high
 * 32 bits to a roaringbitmap. Since the OSM IDs are concentrated towards the bottom of the long
 * space (i.e. they only need, so far, one more bit than an int provides), only a few blocks are used.
 *
 * This home-made implementation is probably not particularly fast, and the library now has a 64 bit mode:
 * https://github.com/RoaringBitmap/RoaringBitmap#64-bit-integers-long
 * TODO convert to standard 64-bit extension in library
 */
public class NodeTracker {

    private static final Logger LOG = LoggerFactory.getLogger(NodeTracker.class);

    private Map<Integer, RoaringBitmap> blocks = new HashMap<>();

    public void add(long x) {
        int high = highIndex(x);
        int low = lowIndex(x);

        RoaringBitmap block = blocks.get(high);

        if (block == null) {
            block = new RoaringBitmap();
            blocks.put(high, block);
        }

        block.add(low);
    }

    public boolean contains(long x) {
        int high = highIndex(x);
        RoaringBitmap block = blocks.get(high);
        if (block != null) {
            int low = lowIndex(x);
            return block.contains(low);
        } else {
            return false; // block not found
        }
    }

    public int cardinality () {
        // cardinality is the sum of the cardinality of all member bitmaps
        return blocks.values().stream()
                .mapToInt(RoaringBitmap::getCardinality)
                .sum();
    }

    private static int highIndex (long key) {
        return (int) (key >> 32);
    }

    private static int lowIndex (long key) {
        // this truncation can change the sign of the int, but roaringbitmaps treats ints as unsigned, so that's
        // not a problem.
        return (int) key;
    }

    public static NodeTracker acceptEverything() {
        return new NodeTracker() {
            @Override
            public boolean contains(long x) {
                return true;
            }
        };
    }
}


// Scan through nodes, marking those that are within the bbox.
// Then load ways, keeping only those that contain at least one marked node.
// Finally, load all nodes that are in any of those ways.

// Filters: bbox and tags.
// Use C PBF converter to pre-filter the data. Toolchains.
