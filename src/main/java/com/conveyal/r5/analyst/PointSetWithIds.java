package com.conveyal.r5.analyst;

import java.io.Serializable;
import java.util.List;
import com.conveyal.r5.analyst.PointWithId;

/**
 * A pointset that represents a list of points with IDs.
 * For example, this is used to represent interior points of census dissemination areas, where
 * the ID is the census dissemination area ID.
 *
 * This allows for doing one-to-many routing to an arbitrary list of points, rather than isochrone
 * generation that calculates times to every single map pixel.
 */
public class PointSetWithIds extends PointSet implements Serializable {
    public static final long serialVersionUID = 1L;

    public List<PointWithId> points;

    public PointSetWithIds(List<PointWithId> points) {
        this.points = points;
    }

    @Override
    public int featureCount() {
        return points == null ? 0 : points.size();
    }

    @Override
    public double getLat(int i) {
        return points.get(i).getCoordinates().getLatitude();
    }

    @Override
    public double getLon(int i) {
        return points.get(i).getCoordinates().getLongitude();
    }

    public String getId(int i) {
        return points.get(i).getId();
    }
}
