package com.conveyal.r5.shapefile;

import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;

import java.util.Objects;

public class NodeDeduplicator {

    TObjectLongMap<BinKey> osmNodeForBin = new TObjectLongHashMap<>();

    private long nextTempOsmId = -1;

    private static final double roundingMultiplier = 1;

    long getNodeForBin(double lat, double lon) {
        BinKey binKey = new BinKey(lat, lon);
        long nodeId = osmNodeForBin.get(binKey);
        if (nodeId == 0) {
            nodeId = nextTempOsmId;
            nextTempOsmId -= 1;
            osmNodeForBin.put(binKey, nodeId);
        }
        return nodeId;
    }

    private static class BinKey {
        private int xBin;
        private int yBin;

        public BinKey (double x, double y) {
            this.xBin = (int)(x * roundingMultiplier);
            this.yBin = (int)(y * roundingMultiplier);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BinKey key = (BinKey) o;
            return xBin == key.xBin &&
                    yBin == key.yBin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(xBin, yBin);
        }

        @Override
        public String toString() {
            return "(" + xBin + "," + yBin + ')';
        }
    }

}
