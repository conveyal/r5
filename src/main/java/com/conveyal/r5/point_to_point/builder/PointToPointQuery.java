package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;

/**
 * Class which will make point to point or profile queries on Transport network based on profileRequest
 * Created by mabu on 23.12.2015.
 */
public class PointToPointQuery {
    private final TransportNetwork transportNetwork;

    public PointToPointQuery(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    public ProfileResponse getPlan(ProfileRequest request) {
        //Do the query and return result
        ProfileResponse profileResponse = new ProfileResponse();

        return profileResponse;
    }
}
