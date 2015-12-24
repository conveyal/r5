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
        //Do the query and return result
        ProfileResponse profileResponse = new ProfileResponse();

        boolean transit = request.useTransit();
        StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
        EnumSet<Mode> modes = transit ? request.accessModes : request.directModes;
        if (modes.contains(Mode.CAR))
            streetRouter.mode = Mode.CAR;
        else if (modes.contains(Mode.BICYCLE))
            streetRouter.mode = Mode.BICYCLE;
        else
            streetRouter.mode = Mode.WALK;

        streetRouter.profileRequest = request;

        //Split for end coordinate
        Split split = transportNetwork.streetLayer.findSplit(request.toLat, request.toLon,
            RADIUS_METERS);
        if (split == null) {
            throw new RuntimeException("Edge near the end coordinate wasn't found. Routing didn't start!");
        }
        // TODO add time and distance limits to routing, not just weight.
        // TODO apply walk and bike speeds and maxBike time.
        streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
        if(!streetRouter.setOrigin(request.fromLat, request.fromLon)) {
            throw  new RuntimeException("Edge near the origin coordinate wasn't found. Routing didn't start!");
        }
        streetRouter.route();
        if (transit) {
            LOG.warn("Transit routing doesn't work yet");
        } else {
            StreetRouter.State lastState = streetRouter.getState(split);
            if (lastState != null) {
                StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                StreetSegment streetSegment = new StreetSegment(streetPath);
                ProfileOption option = new ProfileOption();
                option.addDirect(streetSegment, request.getFromTimeDateZD());
                option.summary = option.generateSummary();
                profileResponse.addOption(option);

            }
        }

        return profileResponse;
    }
}
