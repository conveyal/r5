package com.conveyal.gtfs.validator.service;

import org.locationtech.jts.geom.Coordinate;
import org.opengis.referencing.operation.MathTransform;

public class ProjectedCoordinate extends Coordinate {

  private static final long serialVersionUID = 2905131060296578237L;

  final private MathTransform transform;
  final private Coordinate refLatLon;

  public ProjectedCoordinate(MathTransform mathTransform,
    Coordinate to, Coordinate refLatLon) {
    this.transform = mathTransform;
    this.x = to.x;
    this.y = to.y;
    this.refLatLon = refLatLon;
  }

  public String epsgCode() {
    final String epsgCode =
        "EPSG:" + GeoUtils.getEPSGCodefromUTS(refLatLon);
    return epsgCode;
  }

  public Coordinate getReferenceLatLon() {
    return refLatLon;
  }

  public MathTransform getTransform() {
    return transform;
  }
  
  public double getX()
  {
	  return this.x;
  }
  
  public double getY()
  {
	  return this.y;
  }


}
