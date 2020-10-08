package com.conveyal.data.geobuf;

import geobuf.Geobuf;
import org.geotools.feature.type.GeometryTypeImpl;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A representation of a GeoBuf feature.
 */
public class GeobufFeature implements Cloneable, Serializable {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(GeobufFeature.class);

    public Geometry geometry;
    public Map<String, Object> properties;
    public String id;
    public long numericId;

    private static final GeometryFactory geometryFactory = new GeometryFactory();

    public GeobufFeature(SimpleFeature simpleFeature) {
        this.geometry = (Geometry) simpleFeature.getDefaultGeometry();
        this.properties = new HashMap<>();
        this.id = simpleFeature.getID();

        // copy over attributes
        for (Property p :simpleFeature.getProperties()) {
            if (p.getType() instanceof GeometryTypeImpl)
                continue;

            this.properties.put(p.getName().toString(), p.getValue());
        }
    }

    public GeobufFeature () {}

    /** decode a feature from GeoBuf, passing in the keys in the file and the precision divison (e.g. 1e6 for precision 6) */
    public GeobufFeature(Geobuf.Data.Feature feature, List<String> keys, double precisionDivisor) {
        // easy part: parse out the properties
        this.properties = new HashMap<>();

        for (int i = 0; i < feature.getPropertiesCount(); i += 2) {
            Geobuf.Data.Value val = feature.getValues(feature.getProperties(i + 1));

            Object valObj = null;

            if (val.hasBoolValue())
                valObj = val.getBoolValue();
            else if (val.hasDoubleValue())
                valObj = val.getDoubleValue();
            else if (val.hasNegIntValue())
                valObj = val.getNegIntValue();
            else if (val.hasPosIntValue())
                valObj = val.getPosIntValue();
            else if (val.hasStringValue())
                valObj = val.getStringValue();

            this.properties.put(keys.get(feature.getProperties(i)), valObj);
        }

        // parse geometry
        Geobuf.Data.Geometry gbgeom = feature.getGeometry();

        if (Geobuf.Data.Geometry.Type.MULTIPOLYGON.equals(gbgeom.getType()))
            this.geometry = decodeMultipolygon(gbgeom, precisionDivisor);
        else
            LOG.warn("Unsupported geometry type {}", gbgeom.getType());

        // parse ID
        if (feature.hasIntId())
            this.numericId = feature.getIntId();
        else
            this.id = feature.getId();
    }

    private Geometry decodeMultipolygon(Geobuf.Data.Geometry gbgeom, double precisionDivisor) {
        // decode multipolygon one polygon at a time
        // first length is number of polygons, next is number of rigns, number of coordinates for each ring,
        // number of rings, number of coordinates for each ring . . .
        int len = 0, coordGlobalIdx = 0;
        int npoly = gbgeom.getLengths(len++);

        Polygon[] polygons = new Polygon[npoly];

        for (int poly = 0; poly < npoly; poly++) {
            int nring = gbgeom.getLengths(len++);

            if (nring < 1) {
                LOG.warn("Polygon has zero rings");
                continue;
            }

            // geobuf treats the exterior as ring 0, while JTS treats it as a separate entity
            LinearRing shell = null;
            LinearRing[] holes = new LinearRing[nring - 1];

            for (int ring = 0; ring < nring; ring++) {
                int ncoord = gbgeom.getLengths(len++);
                Coordinate[] coords = new Coordinate[ncoord + 1];

                long prevx = 0, prevy = 0;

                for (int coordRingIdx = 0; coordRingIdx < ncoord; coordRingIdx++) {
                    // TODO more than two dimensions
                    long x = gbgeom.getCoords(coordGlobalIdx++) + prevx;
                    long y = gbgeom.getCoords(coordGlobalIdx++) + prevy;

                    coords[coordRingIdx] = new Coordinate(x / precisionDivisor, y / precisionDivisor);

                    prevx = x;
                    prevy = y;
                }

                // JTS wants closed polygons
                coords[ncoord] = coords[0];

                LinearRing theRing = geometryFactory.createLinearRing(coords);

                if (ring == 0)
                    shell = theRing;
                else
                    holes[ring - 1] = theRing;
            }

            polygons[poly] = geometryFactory.createPolygon(shell, holes);
        }

        return geometryFactory.createMultiPolygon(polygons);
    }

    /** return a copy of this object (also makes a defensive copy of properties, but not of the geometry as the geometry is considered immutable) */
    public GeobufFeature clone () {
        GeobufFeature ret;
        try {
            ret = (GeobufFeature) super.clone();
        } catch (CloneNotSupportedException e) {
            // contact spock immediately
            throw new RuntimeException(e);
        }

        ret.properties = new HashMap<>();
        ret.properties.putAll(this.properties);

        // no need to clone geometry as it's immutable

        return ret;
    }
}
