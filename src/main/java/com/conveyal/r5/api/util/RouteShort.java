package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.conveyal.gtfs.model.Route;
import com.sun.istack.internal.NotNull;

import java.util.Collection;
import java.util.List;

public class RouteShort {

    /**
     * Route ID
     * @notnull
     */
    public String id;

    /**
     * Short name of the route. Usually number or number plus letter
     */
    @NotNull
    public String shortName;

    /**
     * Full, more descriptive name of the route
     */
    @NotNull
    public String longName;

    /**
     * Type of transportation used on a route
     */
    @NotNull
    public String mode;

    /**
     * Color that corresponds to a route (it needs to be character hexadecimal number) (00FFFF)
     * @default: FFFFFF
     */
    public String color;

    /**
     * Full name of the transit agency for this route
     * @notnull
     */
    public String agencyName;

    public RouteShort() {

    }

    public RouteShort (Route route) {
        id = route.route_id;
        shortName = route.route_short_name;
        longName = route.route_long_name;
        //FIXME: add mode
        //mode = GtfsLibrary.getTraverseMode(route).toString();
        mode = "UNKNOWN";
        color = route.route_color;
        agencyName = route.agency.agency_name;
    }

    public static List<RouteShort> list (Collection<Route> in) {
        List<RouteShort> out = Lists.newArrayList();
        for (Route route : in) out.add(new RouteShort(route));
        return out;
    }


    @Override
    public String toString() {
        return "RouteShort{" +
            "id='" + id + '\'' +
            ", shortName='" + shortName + '\'' +
            ", longName='" + longName + '\'' +
            ", mode='" + mode + '\'' +
            ", color='" + color + '\'' +
            ", agencyName='" + agencyName + '\'' +
            '}';
    }


}
