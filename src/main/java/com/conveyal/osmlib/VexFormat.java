package com.conveyal.osmlib;

public abstract class VexFormat {

    public static final byte[] HEADER = "VEXFMT".getBytes();

    // FIXME use OSMEntity.Type or Classes themselves
    public static final int VEX_NODE = 0;
    public static final int VEX_WAY = 1;
    public static final int VEX_RELATION = 2;
    public static final int VEX_NONE = 3;

    // TODO OSM layer support
    public static final int LAYER_ANY = 0;
    public static final int LAYER_STREET = 1;
    public static final int LAYER_LANDUSE = 2;
    public static final int LAYER_BUILDING = 3;

    // TODO helper methods that give etype for class, int for etype, etc.

}
