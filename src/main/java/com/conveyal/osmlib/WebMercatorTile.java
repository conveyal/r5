package com.conveyal.osmlib;

/**
 *
 */
public class WebMercatorTile {

    //http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
    public final int ZOOM = 12;
    public final int xtile, ytile;

    /**
     * Tile definition equations from: TODO URL
     */
    public WebMercatorTile(double lat, double lon) {
        xtile = (int) Math.floor((lon + 180) / 360 * (1 << ZOOM));
        ytile = (int) Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat))
                + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 << ZOOM));
    }

}
