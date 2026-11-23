package com.conveyal.gtfs.geom;

/// A polygon with holes in it. Adds more linear rings punching holes in an outer shell.
public class CPolygonWithHoles extends CPolygon {

    /// Maybe these should just be double[] arrays.
    /// Not sure the linear ring abstraction adds anything over polygon-without-holes.
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
