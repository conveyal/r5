package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.List;

public class Route {

    /**
     * Route ID
     * @notnull
     */
    public String id;

    /**
     * Short name of the route. Usually number or number plus letter
     * @notnull
     */
    public String shortName;

    /**
     * Full, more descriptive name of the route
     * @notnull
     */
    public String longName;

    public String description;

    /**
     * Type of transportation used on a route
     * @notnull
     */
    public TransitModes mode;

    /**
     * Color that corresponds to a route (it needs to be character hexadecimal number) (00FFFF)
     * @default: FFFFFF
     */
    @JsonProperty("color")
    public String routeColor;

    /**
     * Color that is used for text in route (it needs to be character hexadecimal number)
     * @default: 000000
     */
    public String textColor;

    /**
     * URL with information about route
     */
    public String url;

    /**
     * Full name of the transit agency for this route
     * @notnull
     */
    public String agencyName;

    public Route() {

    }

    public Route(com.conveyal.gtfs.model.Route route) {
        id = route.route_id;
        shortName = route.route_short_name;
        longName = route.route_long_name;
        //FIXME: add mode
        //mode = GtfsLibrary.getTraverseMode(route).toString();
        mode = TransitModes.BUS;
        routeColor = route.route_color;
        agencyName = route.agency.agency_name;
    }

    public static List<Route> list (Collection<com.conveyal.gtfs.model.Route> in) {
        List<Route> out = Lists.newArrayList();
        for (com.conveyal.gtfs.model.Route route : in) out.add(new Route(route));
        return out;
    }


    @Override
    public String toString() {
        return "RouteShort{" +
            "id='" + id + '\'' +
            ", shortName='" + shortName + '\'' +
            ", longName='" + longName + '\'' +
            ", mode='" + mode + '\'' +
            ", color='" + routeColor + '\'' +
            ", agencyName='" + agencyName + '\'' +
            '}';
    }


}
