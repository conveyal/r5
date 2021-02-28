package com.conveyal.analysis.util;

import org.locationtech.jts.geom.Envelope;

public class MapTile {

    // http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
    // http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Java

    private final int zoom;
    private final int x;
    private final int y;

    public MapTile (int zoom, int x, int y) {
        this.zoom = zoom;
        this.x = x;
        this.y = y;
    }

    // Add equals and hashcode if these are serving as keys.

//    public static int xTile(double lon, int zoom) {
//        return (int) Math.floor((lon + 180) / 360 * (1 << zoom));
//    }
//
//    public static int yTile(double lat, int zoom) {
//        return (int) Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat))
//                + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 << zoom));
//    }
//

    public Envelope wgsEnvelope () {
        return wgsEnvelope(zoom, x, y);
    }

    // Create an envelope in WGS84 coordinates for the given map tile numbers.
    public static Envelope wgsEnvelope (final int zoom, final int x, final int y) {
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);
        return new Envelope(west, east, south, north);
    }

    public static double tile2lon(int xTile, int zoom) {
        return xTile / Math.pow(2.0, zoom) * 360.0 - 180;
    }

    public static double tile2lat(int yTile, int zoom) {
        double n = Math.PI - (2.0 * Math.PI * yTile) / Math.pow(2.0, zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

}
