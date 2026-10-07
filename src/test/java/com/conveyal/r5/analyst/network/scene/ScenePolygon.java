package com.conveyal.r5.analyst.network.scene;

/// Represents a polygon in a Scene, usually used for on-demand pick-up or drop-off areas.
/// Rendered into the GTFSFeed as a Flex location (equivalent to one feature in locations.geojson).
public class ScenePolygon {

    /// Used as both the id and name for a GTFS location.
    public final String id;

    /// Packed (x, y) coordinates of the polygon's outer ring, in meters.
    /// The ring is closed, i.e. its first point is repeated at the end.
    /// Additional rings for holes are not supported.
    final double[] ringXY;

    /// Construct a ScenePolygon from an unclosed packed (x, y) meter coordinate array,
    /// which will be closed by the constructor.
    ScenePolygon (String id, int[] unclosedXY) {
        if (unclosedXY.length % 2 != 0) {
            throw new IllegalArgumentException("ScenePolygon coordinates must be (x, y) pairs (odd number given).");
        }
        if (unclosedXY.length < 6) {
            throw new IllegalArgumentException("ScenePolygon requires at least three points.");
        }
        this.id = id;
        this.ringXY = new double[unclosedXY.length + 2];
        for (int i = 0; i < unclosedXY.length; i++) {
            ringXY[i] = unclosedXY[i];
        }
        ringXY[unclosedXY.length] = unclosedXY[0];
        ringXY[unclosedXY.length + 1] = unclosedXY[1];
    }

    @Override
    public String toString () {
        return String.format("ScenePolygon %s (%d points)", id, ringXY.length / 2 - 1);
    }

}
