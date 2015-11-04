package com.conveyal.r5.analyst;

import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.vividsolutions.jts.geom.Coordinate;

/**
 * Created by matthewc on 10/29/15.
 */
public interface PointSet {
    int featureCount();

    Coordinate getCoordinate(int index);

    LinkedPointSet link (StreetLayer streetLayer);

    double getLat(int i);

    double getLon(int i);
}
