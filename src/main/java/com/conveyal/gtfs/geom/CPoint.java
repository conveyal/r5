package com.conveyal.gtfs.geom;

/// A single 2D point. Used only for the position of pointlike objects. Not used internally by
/// CLineStrings, CLinearRings, etc. which have packed primitive arrays rather than nested CPoints.
public record CPoint (double lon, double lat) {

}
