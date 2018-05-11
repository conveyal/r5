package com.conveyal.r5.analyst;

import org.geojson.LngLatAlt;
import org.geojson.Point;

public class PointWithId extends Point {
    private String id;

    public PointWithId(String id, double lat, double lng) {
        this.id = id;
        this.setCoordinates(new LngLatAlt(lng, lat));
    }
    public String getId() {
        return id;
    }

}
