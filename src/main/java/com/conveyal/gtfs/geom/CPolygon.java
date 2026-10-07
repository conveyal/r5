package com.conveyal.gtfs.geom;

import java.util.List;
import java.util.stream.Collectors;

/// A polygon with no holes is basically the same thing as a linear ring. Adding holes
/// will further specialize it, so this superclass should not be called "simple" by contrast.
public class CPolygon extends CLinearRing implements CPolygonal {
    public CPolygon (double[] packedCoords) {
        super(packedCoords);
    }

    public boolean hasHoles () {
        return false;
    }

    public CLinearRing[] getHoles () {
        return new CLinearRing[0];
    }

    /// Factory method to return the right subclass from an List of arrays of packed coordinates for
    /// linear rings. First element is the outer shell, subsequent elements are holes in the shell.
    public static CPolygon fromRings (List<double[]> ringPackedCoords) {
        double[] shell = ringPackedCoords.get(0);
        int nHoles = ringPackedCoords.size() - 1;
        if (nHoles > 0) {
            CLinearRing[] holes = new CLinearRing[nHoles];
            for (int i = 0; i < nHoles; i++) {
                holes[i] = new CLinearRing(ringPackedCoords.get(i + 1));
            }
            return new CPolygonWithHoles(shell, holes);
        }
        return new CPolygon(shell);
    }

    /// Throwaway conversion to JTS for through validation.
    /// Validation at ring and polygon construction looks only at closed rings and number of points,
    /// the same things we check. Is more validated here?
    public boolean validate () {
        return JTSConverter.toJts(this).isValid();
    }

}
