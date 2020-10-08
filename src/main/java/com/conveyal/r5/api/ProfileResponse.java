package com.conveyal.r5.api;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.ProfileOption;
import com.conveyal.r5.api.util.StreetSegment;
import com.conveyal.r5.api.util.Transfer;
import com.conveyal.r5.api.util.TransitJourneyID;
import com.conveyal.r5.api.util.TripPattern;
import com.conveyal.r5.profile.HashPath;
import com.conveyal.r5.profile.PathWithTimes;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.fare.DCFareCalculator;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.r5.transit.TransitLayer.TRANSFER_DISTANCE_LIMIT_METERS;

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
     * Each access and egress Mode path combination is reconstructed from StreetRouter only once per profileOption
     *
     * Access and egress paths are also inserted only once per mode and stopIndex.
     * @param accessRouter map of modes to each street router for access paths
     * @param egressRouter map of modes to each street router for access paths
     * @param currentTransitPath transit path with transfers stops and times
     * @param transportNetwork which is used to get stop names, etc.
     * @param fromTimeDateZD this is used to get date
     */
    public void addTransitPath(Map<LegMode, StreetRouter> accessRouter,
                               Map<LegMode, StreetRouter> egressRouter, PathWithTimes currentTransitPath,
                               TransportNetwork transportNetwork, ZonedDateTime fromTimeDateZD) {

        HashPath hashPath = new HashPath(currentTransitPath);
        ProfileOption profileOption = transitToOption.getOrDefault(hashPath, new ProfileOption());


        if (profileOption.isEmpty()) {
            LOG.debug("Creating new profile option");
            options.add(profileOption);
        }

        int startStopIndex = currentTransitPath.boardStops[0];
        int endStopIndex = currentTransitPath.alightStops[currentTransitPath.length-1];
        int startVertexStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(startStopIndex);
        int endVertexStopIndex = transportNetwork.transitLayer.streetVertexForStop.get(endStopIndex);
        //LOG.info("Filling response access paths:");
        //TODO: update this so that each stopIndex and mode pair is changed to streetpath only once
        LegMode accessMode = currentTransitPath.accessMode;
        if (accessMode != null) {
            int accessPathIndex = profileOption.getAccessIndex(accessMode, startVertexStopIndex);
            if (accessPathIndex < 0) {
                //Here accessRouter needs to have this access mode since stopModeAccessMap is filled from accessRouter
                StreetRouter streetRouter = accessRouter.get(accessMode);
                //FIXME: Must we really update this on every streetrouter?
                streetRouter.profileRequest.reverseSearch = false;
                StreetRouter.State state = streetRouter.getStateAtVertex(startVertexStopIndex);
                if (state != null) {
                    StreetPath streetPath;
                    if ((accessMode == LegMode.CAR_PARK || accessMode == LegMode.BICYCLE_RENT) && streetRouter.previousRouter != null) {
                        streetPath = new StreetPath(state, streetRouter, accessMode,
                            transportNetwork);
                    } else {
                        streetPath = new StreetPath(state, transportNetwork, false);
                    }
                    StreetSegment streetSegment = new StreetSegment(streetPath, accessMode, transportNetwork.streetLayer);
                    profileOption.addAccess(streetSegment, accessMode, startVertexStopIndex);
                    //This should never happen since stopModeAccessMap is filled from reached stops in accessRouter
                } else {
                    LOG.warn("Access: Last state not found for mode:{} stop:{}({})", accessMode, startVertexStopIndex, startStopIndex);
                }
            }
        } else {
            LOG.warn("Mode is not in stopModeAccessMap for start stop:{}({})", startVertexStopIndex, startStopIndex);
        }

        //LOG.info("Filling response EGRESS paths:");
        LegMode egressMode = currentTransitPath.egressMode;
        if (egressMode != null) {
            int egressPathIndex = profileOption.getEgressIndex(egressMode, endVertexStopIndex);
            if (egressPathIndex < 0) {
                //Here egressRouter needs to have this egress mode since stopModeEgressMap is filled from egressRouter
                StreetRouter streetRouter = egressRouter.get(egressMode);
                //FIXME: Must we really update this on every streetrouter?
                streetRouter.profileRequest.reverseSearch = true;
                StreetRouter.State state = streetRouter.getStateAtVertex(endVertexStopIndex);
                if (state != null) {
                    StreetPath streetPath = new StreetPath(state, transportNetwork, true);
                    StreetSegment streetSegment = new StreetSegment(streetPath, egressMode, transportNetwork.streetLayer);
                    profileOption.addEgress(streetSegment, egressMode, endVertexStopIndex);
                    //This should never happen since stopModeEgressMap is filled from reached stops in egressRouter
                } else {
                    LOG.warn("EGRESS: Last state not found for mode:{} stop:{}({})", accessMode, endVertexStopIndex, endStopIndex);
                }
            }
        } else {
            LOG.warn("Mode is not in stopModeEgressMap for END stop:{}({})", endVertexStopIndex, endStopIndex);
        }
        List<TransitJourneyID> transitJourneyIDs = new ArrayList<>(currentTransitPath.patterns.length);
        for (int i = 0; i < currentTransitPath.patterns.length; i++) {
                profileOption.addTransit(transportNetwork.transitLayer,
                    currentTransitPath, i, fromTimeDateZD, transitJourneyIDs);
            if (i>0) {
                //If there is a transfer between same stops we don't need to walk since we are already there
                if (currentTransitPath.boardStops[i] != currentTransitPath.alightStops[i-1]) {
                    //Adds transfer and transitIndex where it is used (Used when searching for street paths between transit stops)
                    transferToOption.put(new Transfer(currentTransitPath.alightStops[i - 1],
                        currentTransitPath.boardStops[i], i - 1), profileOption);
                }
            }

            patterns.putIfAbsent(currentTransitPath.patterns[i], new TripPattern(transportNetwork.transitLayer,currentTransitPath.patterns[i]));
        }



        profileOption.addItineraries(transitJourneyIDs, transportNetwork.getTimeZone());

        profileOption.summary = profileOption.generateSummary();

        //TODO: this calculates fares last time currentTransitPath is added to this ProfileOption
        //What happens if we use Agency A in first transfer and B in second but at different time
        //Agency A and agency C at next transfer if stops are the same?
        profileOption.fares.addAll(DCFareCalculator.calculateFares(currentTransitPath, transportNetwork));

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
        //LOG.info("Filling middle paths");
        boolean prevReverseSearch = request.reverseSearch;
        request.reverseSearch = false;
        for (Map.Entry<Integer, List<Transfer>> entry: transfersWithSameStart.entrySet()) {
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            streetRouter.streetMode = StreetMode.WALK;
            streetRouter.profileRequest = request;
            //TODO: make configurable distanceLimitMeters in middle
            streetRouter.distanceLimitMeters = TRANSFER_DISTANCE_LIMIT_METERS;
            int stopIndex = transportNetwork.transitLayer.streetVertexForStop.get(entry.getKey());
            streetRouter.setOrigin(stopIndex);
            streetRouter.route();
            //For each transfer with same start stop calculate street path and add it as middle to
            //all the Profileoptions that have this transfer
            for (Transfer transfer: entry.getValue()) {
                int endIndex = transportNetwork.transitLayer.streetVertexForStop.get(transfer.boardStop);
                StreetRouter.State lastState = streetRouter.getStateAtVertex(endIndex);
                if (lastState != null) {
                    StreetPath streetPath = new StreetPath(lastState, transportNetwork, false);
                    StreetSegment streetSegment = new StreetSegment(streetPath, LegMode.WALK, transportNetwork.streetLayer);
                    for (ProfileOption profileOption: transferToOption.get(transfer)) {
                        profileOption.addMiddle(streetSegment, transfer);
                    }
                } else {
                    LOG.warn("Street transfer: {} not found in streetlayer", transfer);
                }
            }
        }
        request.reverseSearch = prevReverseSearch;
    }

    /** Recompute stats for all options, should be done once all options have been added */
    public void recomputeStats (ProfileRequest request) {
        this.options.forEach(o -> o.recomputeStats(request));
    }
}
