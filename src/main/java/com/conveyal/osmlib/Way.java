package com.conveyal.osmlib;

import java.io.Serializable;
import java.util.Arrays;

public class Way extends OSMEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    public long[] nodes;
    
    @Override
    public String toString() {
        return String.format("Way with %d tags and %d nodes", tags.size(), nodes.length);
    }

    @Override
    public Type getType() {
        return Type.WAY;
    }

    @Override
    public boolean equals(Object other) {
        if ( ! (other instanceof Way)) return false;
        Way otherWay = (Way) other;
        return Arrays.equals(this.nodes, otherWay.nodes) && this.tagsEqual(otherWay);
    }

}
