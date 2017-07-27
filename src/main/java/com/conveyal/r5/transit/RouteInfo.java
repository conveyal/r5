package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.Route;

import java.io.Serializable;
import java.net.URL;

/**
 * Information about a route.
 * FIXME This was originally to copy GTFS Route to get rid of inter-object references. Eliminate it.
 */
public class RouteInfo implements Serializable {
    public static final long serialVersionUID = 1L;

    public String agency_id;
    public String agency_name;
    public String route_id;
    public String route_short_name;
    public String route_long_name;
    public int route_type;
    public String color;
    public URL agency_url;

    public RouteInfo (Route route, Agency agency) {
        this.agency_id = route.agency_id;
        this.agency_name = agency.agency_name;
        this.route_id = route.route_id;
        this.route_short_name = route.route_short_name;
        this.route_long_name = route.route_long_name;
        this.route_type = route.route_type;
        this.color = route.route_color;
        this.agency_url = route.route_url;
    }

    public RouteInfo () { /* do nothing */ }
}
