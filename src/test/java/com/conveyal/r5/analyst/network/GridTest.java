package com.conveyal.r5.analyst.network;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

/**
 * Created by abyrd on 2020-11-20
 */
public class GridTest {

    @Test
    public void testGrid () {
        Coordinate aliceSprings = new CoordinateXY(133.87, -23.7);
        Coordinate simpsonDesert = new CoordinateXY(136.5, -25.5);
        GridLayout gridLayout = new GridLayout(simpsonDesert, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addHorizontalRoute(40, 20);
        gridLayout.addVerticalRoute(20, 20);
        gridLayout.addVerticalRoute(40, 20);
        gridLayout.generateNetwork();
    }

}
