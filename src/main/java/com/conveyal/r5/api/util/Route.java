package com.conveyal.r5.api.util;

import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /**
     * Transport network unique integer ID of route
     * @notnull
     */
    public int routeIdx;

    public Route() {

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

    //TODO: we need to decide if we would use Route or RouteInfo since copying RouteInfo data to Route is just stupid
    public static Route from(RouteInfo routeInfo, int routeIndex) {
        Route route = new Route();

        route.shortName = routeInfo.route_short_name;
        route.longName = routeInfo.route_long_name;
        route.id = routeInfo.route_id;
        route.routeColor = routeInfo.color;
        route.mode = TransitLayer.getTransitModes(routeInfo.route_type);
        //FIXME: get from GTFS
        route.agencyName = "UNKNOWN";
        route.routeIdx = routeIndex;

        return route;
    }
}
