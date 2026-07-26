package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFactory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequenceFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/// It may be a good idea to load data into JTS objects before compacting them down to our own
/// format because JTS can perform rigorous validation on its input data.
/// The static final objects here should be reused as much as possible to improve memory access
/// patterns and cut down heap sizes.
public abstract class JTSConverter {

    /// GeoJSON is defined to use spatial reference system 4326, which is WGS84 in (lat, lon) order.
    public static final int SRS_WGS84_LAT_LON = 4326;

    /// The default coordinate array sequence factory uses an implementation with arrays of
    /// references to individual heap-allocated Coordinate instances.
    public static final CoordinateSequenceFactory COORDINATE_SEQUENCE_FACTORY =
          CoordinateArraySequenceFactory.instance();

    /// We will use double-precision floating point coordinates.
    /// Fixed-precision scaled integers are also an option at half the size, but it adds complexity.
    public static final PrecisionModel PRECISION_MODEL = new PrecisionModel(PrecisionModel.FLOATING);

    /// A single static instance of the JTS GeometryFactory, referencing single known instances
    /// of the PrecisionModel and CoordinateSequenceFactory. We use this to ensure that JTS objects
    /// do not each have their own heap-allocated instance of these types upon creation or
    /// deserialization from MapDB or conversion from Conveyal types.
    public static final GeometryFactory GEOMETRY_FACTORY =
          new GeometryFactory(PRECISION_MODEL, SRS_WGS84_LAT_LON, COORDINATE_SEQUENCE_FACTORY);

    /// Use to load straight into our types?
    public static final PackedCoordinateSequenceFactory PACKED_COORDINATE_SEQUENCE_FACTORY =
          PackedCoordinateSequenceFactory.DOUBLE_FACTORY;

    /// Convert a JTS polygon to a compact Conveyal polygon.
    public static CPolygon fromJts (Polygon jtsPolygon) {
        if (jtsPolygon.getDimension() != 2) {
            throw new IllegalArgumentException("Only 2D geometries are supported.");
        }
        CoordinateSequence shellSeq = jtsPolygon.getExteriorRing().getCoordinateSequence();
        double[] shellPackedCoords = toPackedCoordinateArray(shellSeq);
        int nHoles = jtsPolygon.getNumInteriorRing();
        if (nHoles != 0) {
            CLinearRing[] holes = new CLinearRing[nHoles];
            for (int i = 0; i < nHoles; i++) {
                CoordinateSequence cSeq = jtsPolygon.getInteriorRingN(i).getCoordinateSequence();
                double[] packedCoords = toPackedCoordinateArray(cSeq);
                holes[i] = new CLinearRing(packedCoords);
            }
            return new CPolygonWithHoles(shellPackedCoords, holes);
        }
        return new CPolygon(shellPackedCoords);
    }

    public static CPolygonal fromJts (Polygonal jtsPolygonal) {
        return switch (jtsPolygonal) {
            case Polygon p -> fromJts(p);
            case MultiPolygon mp -> fromJts(mp);
            case null, default ->
                  throw new IllegalArgumentException("Only polygon and multipolygon are supported.");
        };
    }

    public static Polygon toJts (CPolygon cPolygon) {
        LinearRing shellRing = jtsRingFromPackedCoords(cPolygon.packedCoords);
        if (cPolygon.hasHoles()) {
            CLinearRing[] cInnerRings = cPolygon.getHoles();
            LinearRing[] innerRings = new LinearRing[cInnerRings.length];
            for (int i = 0; i < cInnerRings.length; i++) {
                innerRings[i] = jtsRingFromPackedCoords(cInnerRings[i].packedCoords);
            }
            return GEOMETRY_FACTORY.createPolygon(shellRing, innerRings);
        } else {
            return GEOMETRY_FACTORY.createPolygon(shellRing);
        }
    }

    /// Convert a compact bounding box to a floating-point WGS84 JTS Envelope.
    public static Envelope toJts (CBox box) {
        return new Envelope(box.minLon, box.maxLon, box.minLat, box.maxLat);
    }

    private static LinearRing jtsRingFromPackedCoords (double[] packedCoords) {
        CoordinateSequence jtsSequence = toJts(packedCoords);
        return GEOMETRY_FACTORY.createLinearRing(jtsSequence);
    }

    public static CoordinateSequence toJts (double[] packedCoords) {
        return PACKED_COORDINATE_SEQUENCE_FACTORY.create(packedCoords, 2);
    }

    /// Convert a JTS coordinate sequence to an array of packed double-precision coordinates.
    /// This is similar to one of several approaches used internally by JTS, but we don't need
    /// need the abstraction to alternatives.Note that this will not make a protective copy if the
    /// source already uses a packed array.
    public static double[] toPackedCoordinateArray (CoordinateSequence cSeq) {
        if (cSeq instanceof PackedCoordinateSequence.Double pcs) {
            return pcs.getRawCoordinates();
        }
        int nCoord = cSeq.size();
        double[] packedCoordinates = new double[nCoord * 2];
        for (int c = 0; c < nCoord; c++) {
            packedCoordinates[c * 2] = cSeq.getX(c);
            packedCoordinates[c * 2 + 1] = cSeq.getY(c);
        }
        return packedCoordinates;
    }

    public static Point pointAt (double lon, double lat) {
        // Illustrating JTS usage friction: to test containment I have to construct a Point object
        // wrapping a Coordinate object built using a GeometryFactory instance. This has a lot to
        // do with envelopes and such auxiliary information being stored on the geometries.
        // Filtering can be efficiently performed by keeping envelope and auxiliary info in a
        // containment filter instance (as for projections to line segments).
        return GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat));
    }

}
