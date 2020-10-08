package com.conveyal.gtfs.api.graphql;

import graphql.schema.Coercing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;

import java.util.stream.Stream;

/**
 * Created by matthewc on 3/9/16.
 */
public class GeoJsonCoercing implements Coercing {
    @Override
    public Object serialize(Object input) {
        // Handle newer org.locationtech JTS LineStrings
        if (input instanceof LineString) {
            GeoJsonLineString ret = new GeoJsonLineString();
            ret.coordinates = Stream.of(((LineString)input).getCoordinates())
                    .map(c -> new double[] { c.x, c.y })
                    .toArray(i -> new double[i][]);

            return ret;
        }
        // Also handle legacy com.vividsolutons JTS LineStrings, which are serialized into our MapDBs
        else if (input instanceof com.vividsolutions.jts.geom.LineString) {
            GeoJsonLineString ret = new GeoJsonLineString();
            ret.coordinates = Stream.of(((com.vividsolutions.jts.geom.LineString) input).getCoordinates())
                    .map(c -> new double[] { c.x, c.y })
                    .toArray(i -> new double[i][]);

            return ret;
        }
        else if (input instanceof MultiPolygon) {
            MultiPolygon g = (MultiPolygon) input;
            GeoJsonMultiPolygon ret = new GeoJsonMultiPolygon();
            ret.coordinates = Stream.of(g.getCoordinates())
                    .map(c -> new double[] { c.x, c.y })
                    .toArray(i -> new double[i][]);

            return ret;
        }
        else return null;
    }

    @Override
    public Object parseValue(Object o) {
        return null;
    }

    @Override
    public Object parseLiteral(Object o) {
        return null;
    }

    private static class GeoJsonLineString {
        public final String type = "LineString";
        public double[][] coordinates;
    }

    private static class GeoJsonMultiPolygon {
        public final String type = "MultiPolygon";
        public double[][] coordinates;
    }
}
