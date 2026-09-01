package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFactory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequenceFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/// Shared JTS conversion infrastructure for the compact geometry classes.
/// Per-type conversions are on the CGeometry classes themselves.
/// The static final JTS objects here should be reused as much as possible to improve memory access
/// patterns and cut down heap sizes when converting large numbers of objects to JTS representation.
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

    /// Convert a compact bounding box to a floating-point WGS84 JTS Envelope.
    public static Envelope toJts (CBox box) {
        return new Envelope(box.minLon, box.maxLon, box.minLat, box.maxLat);
    }

    static LinearRing jtsRingFromPackedCoords (double[] packedCoords) {
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
