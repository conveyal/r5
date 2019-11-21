package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Point;
import gnu.trove.list.TIntList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

/**
 * A PointSet represents a set of geographic points, which serve as destinations or "opportunities" in an
 * accessibility analysis. Legacy Transport Analyst used freeform pointsets; early versions of Conveyal Analysis
 * instead favored regular grids in the web mercator projection.  This abstraction encompasses both.
 * In a future refactor, PointSet should probably become an interface to hide all this spatial indexing and such.
 */
public abstract class PointSet {

    /**
     * It seems like fighting Java typing to store type codes in JSON.
     * But at least by using some symbolic constants and Java identifiers things are well cross-referenced.
     */
    public enum Format {
        FREEFORM (FreeFormPointSet.fileExtension),
        GRID (Grid.fileExtension);
        public final String fileExtension;
        Format(String fileExtension) {
            this.fileExtension = fileExtension;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PointSet.class);

    /** Human readable name. Unfortunately this is lost when persisting Grids, to maintain backward compatibility. */
    public transient String name;

    /**
     * Returns a list of indexes for all points in the PointSet that are at least partially inside the envelope.
     * This may overselect or contain duplicate point indexes (though implementations should minimize those effects).
     * TODO: Add tests for implementation(s).
     *
     * @param envelope the envelope in FIXED POINT DEGREES within which we want to find all points.
     * @return a list of indexes for all points in the PointSet at least partially inside the envelope.
     */
    public TIntList getPointsInEnvelope(Envelope envelope) {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the WGS84 latitude of point i in the PointSet. In the general case, all PointSets (even those on grids)
     *         are treated as flattened one-dimensional arrays.
     */
    public abstract double getLat(int i);

    /**
     * @return the WGS84 longitude of point i in the PointSet. In the general case, all PointSets (even those on grids)
     *         are treated as flattened one-dimensional arrays.
     */
    public abstract double getLon(int i);

    /**
     * @return the total number of points in the PointSet. In the general case, all PointSets (even those on grids) are
     *         treated as flattened one-dimensional arrays, so a gridded PointSet has (width * height) points.
     */
    public abstract int featureCount();

    /**
     * @return the sum of the opportunity counts at all points in this PointSet.
     */
    public abstract double sumTotalOpportunities();

    /**
     * @param i the one-dimensional index into the list of points.
     * @return the quantity or magnitude of opportunities at that point (e.g. jobs, people)
     */
    public abstract double getOpportunityCount(int i);

    /**
     * Returns a new coordinate object for the feature at the given index in this set, or its centroid,
     * in FIXED POINT DEGREES.
     */
    public Coordinate getCoordinateFixed(int index) {
        return new Coordinate(floatingDegreesToFixed(getLon(index)), floatingDegreesToFixed(getLat(index)));
    }

    /**
     * Returns a new coordinate object for the feature at the given index in this set, or its centroid,
     * in FIXED POINT DEGREES.
     */
    public Point getJTSPointFixed(int index) {
        return GeometryUtils.geometryFactory.createPoint(getCoordinateFixed(index));
    }

}
