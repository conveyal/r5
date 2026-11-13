package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

/// A polygon with no holes consists of one linear ring, so it shares the CPackedCoords representation.
/// It is deliberately not a subtype of CLinearRing so each class's toJts return the narrowest JTS type.
public class CPolygon extends CPackedCoords implements CPolygonal {
    public CPolygon (double[] packedCoords) {
        super(packedCoords);
        CLinearRing.checkClosed(packedCoords);
    }

    @Override
    public Polygon toJts () {
        LinearRing shellRing = JTSConverter.jtsRingFromPackedCoords(packedCoords);
        if (hasHoles()) {
            CLinearRing[] cInnerRings = getHoles();
            LinearRing[] innerRings = new LinearRing[cInnerRings.length];
            for (int i = 0; i < cInnerRings.length; i++) {
                innerRings[i] = JTSConverter.jtsRingFromPackedCoords(cInnerRings[i].packedCoords);
            }
            return JTSConverter.GEOMETRY_FACTORY.createPolygon(shellRing, innerRings);
        }
        return JTSConverter.GEOMETRY_FACTORY.createPolygon(shellRing);
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

    public static CPolygon fromJts (Polygon jtsPolygon) {
        if (jtsPolygon.getDimension() != 2) {
            throw new IllegalArgumentException("Only 2D geometries are supported.");
        }
        CoordinateSequence shellSeq = jtsPolygon.getExteriorRing().getCoordinateSequence();
        double[] shellPackedCoords = JTSConverter.toPackedCoordinateArray(shellSeq);
        int nHoles = jtsPolygon.getNumInteriorRing();
        if (nHoles != 0) {
            CLinearRing[] holes = new CLinearRing[nHoles];
            for (int i = 0; i < nHoles; i++) {
                CoordinateSequence cSeq = jtsPolygon.getInteriorRingN(i).getCoordinateSequence();
                double[] packedCoords = JTSConverter.toPackedCoordinateArray(cSeq);
                holes[i] = new CLinearRing(packedCoords);
            }
            return new CPolygonWithHoles(shellPackedCoords, holes);
        }
        return new CPolygon(shellPackedCoords);
    }

}
