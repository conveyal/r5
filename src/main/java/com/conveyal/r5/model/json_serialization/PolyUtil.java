package com.conveyal.r5.model.json_serialization;

/*
 * ADAPTED BY CONVEYAL FROM:
 * https://github.com/googlemaps/android-maps-utils/blob/master/library/src/com/google/maps/android/PolyUtil.java
 * Copyright 2008, 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;

import java.util.ArrayList;
import java.util.List;

public class PolyUtil {

    private PolyUtil() {}

    /** Decodes an encoded path string into a sequence of LatLngs. */
    public static CoordinateSequence decode(final String encodedPath) {
        int len = encodedPath.length();
        final List<Coordinate> path = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lng = 0;

        while (index < len) {
            int result = 1;
            int shift = 0;
            int b;
            do {
                b = encodedPath.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            result = 1;
            shift = 0;
            do {
                b = encodedPath.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f);
            lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            path.add(new Coordinate(lng * 1e-5, lat * 1e-5));
        }
        return new PackedCoordinateSequence.Double(path.toArray(new Coordinate[path.size()]));
    }

    public static String encode(final LineString ls) {
        return encode(ls.getCoordinateSequence());
    }

    public static String encode(final CoordinateSequence cs) {
        return encode(cs.toCoordinateArray());
    }

    /** Encodes a sequence of LatLngs into an encoded path string. */
    public static String encode(final Coordinate[] coords) {

        long lastLat = 0;
        long lastLng = 0;

        final StringBuffer result = new StringBuffer();

        for (final Coordinate point : coords) {
            long lat = Math.round(point.y * 1e5);
            long lng = Math.round(point.x * 1e5);

            long dLat = lat - lastLat;
            long dLng = lng - lastLng;

            encode(dLat, result);
            encode(dLng, result);

            lastLat = lat;
            lastLng = lng;
        }
        return result.toString();
    }

    private static void encode(long v, StringBuffer result) {
        v = v < 0 ? ~(v << 1) : v << 1;
        while (v >= 0x20) {
            result.append(Character.toChars((int) ((0x20 | (v & 0x1f)) + 63)));
            v >>= 5;
        }
        result.append(Character.toChars((int) (v + 63)));
    }
}
