package com.conveyal.r5.api;

import com.conveyal.r5.api.util.*;
import com.conveyal.r5.profile.HashPath;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Created by mabu on 30.10.2015.
 */
public class ProfileResponse {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileResponse.class);
    public List<ProfileOption> options = new ArrayList<>();

    @Override public String toString() {
        return "ProfileResponse{" +
            "options=" + options +
            '}';
    }
    //This connect which transits are in which profileOption
    private Map<HashPath, ProfileOption> transitToOption = new HashMap<>();

    public List<ProfileOption> getOptions() {
        return options;
    }

    public List<SegmentPattern> getPatterns() {
        Map<String, SegmentPattern> patterns = new HashMap<>(10);

        for (ProfileOption option: options) {
            if (option.transit != null && !option.transit.isEmpty()) {
                for (TransitSegment transitSegment: option.transit) {
                    if (transitSegment.segmentPatterns != null && !transitSegment.segmentPatterns.isEmpty()) {
                        for (SegmentPattern segmentPattern : transitSegment.segmentPatterns) {
                            patterns.put(segmentPattern.patternId, segmentPattern);
                        }
                    }
                }
            }
        }

        //TODO: return as a map since I think it will be more usefull but GraphQL doesn't support map
        return new ArrayList<>(patterns.values());
    }

    public void addOption(ProfileOption option) {
        //Adds only non-empty profile options to response
        if (option.access != null && !option.access.isEmpty()) {
            options.add(option);
        }
    }

    /**
     * It creates option, transit/street segment itinerary and segments
     *
     * It creates options, transitSegment, segmentPatterns as needed.
     *
     * Transit paths with same stops but different patterns are in same transitSegment
     * but different segmentPatterns.
     * Paths with same pattern but different times are in same segmentPattern but different times.
     *
     * Access and egress paths are also inserted only once per mode and stopIndex.
     *
     * @param accessRouter map of modes to each street router for access paths
     * @param egressRouter map of modes to each street router for access paths
     * @param currentTransitPath transit path with transfers stops and times
     * @param transportNetwork which is used to get stop names, etc.
     * @param fromTimeDateZD this is used to get date
     */
    public void addTransitPath(Map<LegMode, StreetRouter> accessRouter,
        Map<LegMode, StreetRouter> egressRouter, Path currentTransitPath,
        TransportNetwork transportNetwork, ZonedDateTime fromTimeDateZD) {

        HashPath hashPath = new HashPath(currentTransitPath);
        ProfileOption profileOption = transitToOption.getOrDefault(hashPath, new ProfileOption());


        if (profileOption.isEmpty()) {
            LOG.info("Creating new profile option");
            options.add(profileOption);
        }

        int startStopIndex = currentTransitPath.boardStops[0];
        int endStopIndex = currentTransitPath.alightStops[currentTransitPath.length-1];
        int startVertexStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(startStopIndex);
        int endVertexStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(endStopIndex);
        /*TODO:What happens if we route somewhere with bicycle, walk and transit and if we are walking
        and choose transit option 1 we are too late. (later then requested arrival time),
         but if we choose bicycle we are on time?


          */
        accessRouter.forEach((mode, streetRouter) -> {
            int accessPathIndex = profileOption.getAccessIndex(mode, startVertexStopIndex);
            if (accessPathIndex < 0) {
                StreetRouter.State state = streetRouter.getState(startVertexStopIndex);
                if (state != null) {
                    StreetPath streetPath = new StreetPath(state, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath, mode);
                    profileOption.addAccess(streetSegment, mode, startVertexStopIndex);
                }
            }
        });

        egressRouter.forEach((mode, streetRouter) -> {
            int egressPathIndex = profileOption.getEgressIndex(mode, endVertexStopIndex);
            if (egressPathIndex < 0) {
                StreetRouter.State state = streetRouter.getState(endVertexStopIndex);
                if (state != null) {
                    StreetPath streetPath = new StreetPath(state, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath, mode);
                    profileOption.addEgress(streetSegment, mode, endVertexStopIndex);
                }
            }
        });
        List<TransitJourneyID> transitJourneyIDs = new ArrayList<>(currentTransitPath.patterns.length);
        for (int i = 0; i < currentTransitPath.patterns.length; i++) {
                profileOption.addTransit(transportNetwork.transitLayer,
                    currentTransitPath, i, fromTimeDateZD, transitJourneyIDs);


        }

        profileOption.addItineraries(transitJourneyIDs, transportNetwork.getTimeZone());

        profileOption.summary = profileOption.generateSummary();

        transitToOption.putIfAbsent(hashPath, profileOption);
    }
}
