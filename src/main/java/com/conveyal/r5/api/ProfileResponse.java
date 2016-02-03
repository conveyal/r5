package com.conveyal.r5.api;

import com.conveyal.r5.api.util.*;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by mabu on 30.10.2015.
 */
public class ProfileResponse {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileResponse.class);
    public List<ProfileOption> options = new ArrayList<>();
    private Map<Integer, TripPattern> patterns = new HashMap<>();
    //This is used to find which transfers are used in which Profileoption when calculating street transfers
    private Multimap<Transfer, ProfileOption> transferToOption = HashMultimap.create();

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

    public List<TripPattern> getPatterns() {
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


        TODO: update this so that each stopIndex and mode pair is changed to streetpath only once
          */
        accessRouter.forEach((mode, streetRouter) -> {
            int accessPathIndex = profileOption.getAccessIndex(mode, startVertexStopIndex);
            if (accessPathIndex < 0) {
                StreetRouter.State state = streetRouter.getState(startVertexStopIndex);
                if (state != null) {
                    StreetPath streetPath = new StreetPath(state, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer);
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
                    StreetSegment streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer);
                    profileOption.addEgress(streetSegment, mode, endVertexStopIndex);
                }
            }
        });
        List<TransitJourneyID> transitJourneyIDs = new ArrayList<>(currentTransitPath.patterns.length);
        for (int i = 0; i < currentTransitPath.patterns.length; i++) {
                profileOption.addTransit(transportNetwork.transitLayer,
                    currentTransitPath, i, fromTimeDateZD, transitJourneyIDs);
            if (i>0) {
                //Adds transfer and transitIndex where it is used (Used when searching for street paths between transit stops)
                transferToOption.put(new Transfer(currentTransitPath.alightStops[i-1], currentTransitPath.boardStops[i], i-1), profileOption);
            }

            patterns.putIfAbsent(currentTransitPath.patterns[i], new TripPattern(transportNetwork.transitLayer,currentTransitPath.patterns[i]));
        }



        profileOption.addItineraries(transitJourneyIDs, transportNetwork.getTimeZone());

        profileOption.summary = profileOption.generateSummary();

        transitToOption.putIfAbsent(hashPath, profileOption);
    }

    /**
     * With help of street router generates paths on streetlayer for all transfer combinations.
     *
     * Each path is calculated only once and then added to all options which use it.
     * StreetRouter is called once per start transfer.
     * @param transportNetwork
     * @param request
     */
    public void generateStreetTransfers(TransportNetwork transportNetwork, ProfileRequest request) {
        //Groups transfers on alight stop so that StreetRouter is called only once per start stop
        Map<Integer, List<Transfer>> transfersWithSameStart = transferToOption.keySet().stream()
            .collect(Collectors.groupingBy(Transfer::getAlightStop));
        for (Map.Entry<Integer, List<Transfer>> entry: transfersWithSameStart.entrySet()) {
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            streetRouter.mode = Mode.WALK;
            streetRouter.profileRequest = request;
            //TODO: make configurable distanceLimitMeters in middle
            streetRouter.distanceLimitMeters = 2000;
            int stopIndex = transportNetwork.transitLayer.streetVertexForStop.get(entry.getKey());
            streetRouter.setOrigin(stopIndex);
            streetRouter.route();
            //For each transfer with same start stop calculate street path and add it as middle to
            //all the Profileoptions that have this transfer
            for (Transfer transfer: entry.getValue()) {
                int endIndex = transportNetwork.transitLayer.streetVertexForStop.get(transfer.boardStop);
                StreetRouter.State lastState = streetRouter.getState(endIndex);
                if (lastState != null) {
                    StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath, LegMode.WALK, transportNetwork.streetLayer);
                    for (ProfileOption profileOption: transferToOption.get(transfer)) {
                        profileOption.addMiddle(streetSegment, transfer);
                    }
                } else {
                    LOG.warn("Street transfer: {} not found in streetlayer", transfer);
                }
            }
        }
    }
}
