package com.conveyal.gtfs.geom;

/// A polygon with holes in it. Shares the same representation as the superclass Polygon for the
/// outer ring, but adds more linear rings punching holes in that outer shell. From a class
/// hierarchy point of view adding these holes further specializes CPolygon, so we have avoided
/// naming that superclass something like "simple" or "without holes", ensuring CPolygonWithHoles
/// is-a (is assignable to a) CPolygon which reads better.
public class CPolygonWithHoles extends CPolygon {

    /// We could potentially eliminate the CLinearRing abstraction and just use double[] arrays
    /// or CPolygons here.
    private final CLinearRing[] holes;

    public CPolygonWithHoles (double[] packedCoords, CLinearRing[] holes) {
        super(packedCoords);
        if (holes.length < 1) {
            throw new IllegalArgumentException("A non-simple polygon must have holes in the shell.");
        }
        this.holes = holes;
    }

    @Override
    public boolean hasHoles () {
        return true;
    }

    @Override
    public CLinearRing[] getHoles () {
        return holes;
    }

    public CPolygonWithHoles (CLinearRing shell, CLinearRing[] holes) {
        this(shell.packedCoords, holes);
    }

}
