package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.ProfileOption;
import com.conveyal.r5.api.util.StreetSegment;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * Class which will make point to point or profile queries on Transport network based on profileRequest
 * Created by mabu on 23.12.2015.
 */
public class PointToPointQuery {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointQuery.class);
    public static final int RADIUS_METERS = 200;
    private final TransportNetwork transportNetwork;

    public PointToPointQuery(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    //Does point to point routing with data from request
    public ProfileResponse getPlan(ProfileRequest request) {
        request.zoneId = transportNetwork.getTimeZone();
        //Do the query and return result
        ProfileResponse profileResponse = new ProfileResponse();

        //Split for end coordinate
        Split split = transportNetwork.streetLayer.findSplit(request.toLat, request.toLon,
            RADIUS_METERS);
        if (split == null) {
            throw new RuntimeException("Edge near the end coordinate wasn't found. Routing didn't start!");
        }

        boolean transit = request.useTransit();

        EnumSet<Mode> modes = transit ? request.accessModes : request.directModes;
        ProfileOption option = new ProfileOption();
        //Routes all direct/access modes
        modes.forEach(mode -> {
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            streetRouter.mode = mode;
            streetRouter.profileRequest = request;
            // TODO add time and distance limits to routing, not just weight.
            // TODO apply walk and bike speeds and maxBike time.
            streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
            if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                streetRouter.route();
                StreetRouter.State lastState = streetRouter.getState(split);
                if (lastState != null) {
                    StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath);
                    //TODO: this needs to be different if transit is requested
                    if (transit) {
                        //addAccess
                    } else {
                        option.addDirect(streetSegment, request.getFromTimeDateZD());
                    }

                }
            } else {
                LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
            }
        });
        option.summary = option.generateSummary();
        profileResponse.addOption(option);
        /**
         * TODO: search for transit from all stops accesed in stop trees in access search.
         * add them to options and generate itinerary for each time option
         * add egress part
         */


        return profileResponse;
    }
}
