package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.PathWithTimes;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StatsCalculator;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This is a response model class which holds data that will be serialized and returned to the client.
 * It is not used internally in routing.
 */
public class ProfileOption {
    private static final Logger LOG = LoggerFactory.getLogger(ProfileOption.class);
    //Transit leg of a journey
    public List<TransitSegment> transit;
    //Part of journey from start to transit (or end) @notnull
    public List<StreetSegment> access;
    //Part of journey from transit to end
    public List<StreetSegment> egress;
    //Connects all the trip part to a trip at specific time with specific modes of transportation
    public List<Itinerary> itinerary;
    //Time stats for this part of a journey @notnull
    public Stats stats = new Stats();
    //Text description of this part of a journey @notnull
    public String summary;
    public Set<Fare> fares;

    private transient Map<ModeStopIndex, Integer> accessIndexes = new HashMap<>();
    private transient Map<ModeStopIndex, Integer> egressIndexes = new HashMap<>();

    /**
     * contains full itineraries needed to compute statistics. We don't present these to the client though.
     *
     * There is no way to compute the statistics by combining the statistics of constituent paths. Suppose there
     * are two patterns each running every 15 minutes; we don't know if they are coming in and out of phase.
     * Ergo, we save all itineraries so we can compute fresh stats when we're done.
     *
     * Since all transit paths here will have the same start and end stops, we can save all itineraries individually,
     * we don't need to save their start and end stops as well.
     */
    private transient List<PathWithTimes.Itinerary> fullItineraries = new ArrayList<>();

    @Override public String toString() {
        return "ProfileOption{" +
            " transit=\n   " + transit +
            ", access=\n   " + access +
            ", egress=\n   " + egress +
            ", stats=" + stats +
            ", summary='" + summary + '\'' +
            ", fares=" + fares +
            '}' + "\n";
    }

    /**
     * Initializes access and itinerary since those are non null.
     *
     * Other fields are initialized as needed
     */
    public ProfileOption() {
        access = new ArrayList<>();
        itinerary = new ArrayList<>();
    }

    public boolean isEmpty() {
        return access.isEmpty() && itinerary.isEmpty() && transit == null;
    }

    /** Make a human readable text summary of this option.
     * There are basically four options:
     * - Direct non-transit routes which are named "Non-transit options"
     * - Transit without transfers only on one route which are named "routes [route num]"
     * - Transit without transfers with multiple route options which are named "routes [route num]/[route num]..."
     * - Transit with transfers which are named "routes "[route nun]/[route num], [route num]/[route num] via [STATION NAME]"
     *
     * "/" in name designates OR and "," AND station name is a station where transfer is needed
     * */
    public String generateSummary() {
        if (transit == null || transit.isEmpty()) {
            return "Non-transit options";
        }
        List<String> vias = Lists.newArrayList();
        List<String> routes = Lists.newArrayList();
        for (TransitSegment segment : transit) {
            List<String> routeShortNames = Lists.newArrayList();
            for (Route rs : segment.getRoutes()) {
                String routeName = rs.shortName == null ? rs.longName : rs.shortName;
                routeShortNames.add(routeName);
            }
            routes.add(Joiner.on("/").join(routeShortNames));
            vias.add(segment.to.name);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("routes ");
        sb.append(Joiner.on(", ").join(routes));
        if (!vias.isEmpty()) vias.remove(vias.size() - 1);
        if (!vias.isEmpty()) {
            sb.append(" via ");
            sb.append(Joiner.on(", ").join(vias));
        }
        return sb.toString();
    }

    /**
     * Adds direct routing without transit at provided fromTimeDate
     *
     * This also creates itinerary and adds streetSegment to access list
     * @param streetSegment
     * @param fromTimeDateZD
     */
    public void addDirect(StreetSegment streetSegment, ZonedDateTime fromTimeDateZD) {
        Itinerary itinerary = new Itinerary(streetSegment, access.size(), fromTimeDateZD);
        access.add(streetSegment);
        this.itinerary.add(itinerary);
    }

    private void addTransit(TransitSegment transitSegment) {
        if (transit == null) {
            transit = new ArrayList<>(5);
            fares = new HashSet<>();
        }
        transit.add(transitSegment);
    }

    /**
     * Creates new transit path
     *
     * This creates necessary TransitSegment, segmentPattern and times in segmentPattern
     *
     * For example if you call it twice with same pattern but only different times only times will be added to segmentPattern
     * @param transitLayer transit layer
     * @param currentTransitPath transit path
     * @param pathIndex index of current transit path in currentTransitPath
     * @param fromTimeDateZD date/time object used to get date and timezone
     * @param transitJourneyIDs list of patterns and times in those patterns in this path
     */
    public void addTransit(TransitLayer transitLayer, PathWithTimes currentTransitPath, int pathIndex,
        ZonedDateTime fromTimeDateZD, List<TransitJourneyID> transitJourneyIDs) {
        //If this is first transit in this option or leg that doesn't exist yet we need to create new transitSegment
        if (transit == null || pathIndex >= transit.size()) {
            stats = new Stats();
            stats.max = currentTransitPath.stats.max;
            stats.min = currentTransitPath.stats.min;
            stats.avg = currentTransitPath.stats.avg;
            stats.num = currentTransitPath.length;
            addTransit(new TransitSegment(transitLayer, currentTransitPath, pathIndex, fromTimeDateZD, transitJourneyIDs));
            LOG.debug("Making new transit segment:{}", currentTransitPath);
        } else {
            //Each transitSegment is for each part of transitPath. Since one path consist of multiple transfers.
            TransitSegment transitSegment = transit.get(pathIndex);

            if (transitSegment.hasSameStops(currentTransitPath, pathIndex)) {
                //This adds new segment pattern with times or just new times
                //It allso updates transitJourneyIDs with found/created pattern and time indexes
                transitSegment
                    .addSegmentPattern(transitLayer, currentTransitPath, pathIndex, fromTimeDateZD,
                        transitJourneyIDs);
                LOG.debug("Adding segment pattern to existing transit");
            } else {
                LOG.warn("Incorrect stop in pathIndex:{}", pathIndex);
            }
        }

        fullItineraries.addAll(currentTransitPath.itineraries);
    }

    /**
     * Adds access path if same access path doesn't exist yet in this option
     *
     * Equality is made based on mode and stopIndex
     * @param streetSegment turn by turn information for this access part
     * @param mode which is used on this path
     * @param startVertexStopIndex StreetVertexIndex which is end destination for this path
     * @return index in access array for this path
     */
    public int addAccess(StreetSegment streetSegment, LegMode mode, int startVertexStopIndex) {
        ModeStopIndex modeStopIndex = new ModeStopIndex(mode, startVertexStopIndex);
        int accessIndex;
        if (!accessIndexes.containsKey(modeStopIndex)) {
            access.add(streetSegment);
             accessIndex = (access.size() - 1);
            accessIndexes.put(modeStopIndex, accessIndex);
        } else {
            accessIndex = accessIndexes.get(modeStopIndex);
        }
        return accessIndex;
    }

    /**
     * Adds egress path if same egress path doesn't exist yet in this option
     *
     * Equality is made based on mode and stopIndex
     * @param streetSegment turn by turn information for this egress part
     * @param mode which is used on this path
     * @param endVertexStopIndex StreetVertexIndex which is end destination for this path
     * @return index in egress array for this path
     */
    public int addEgress(StreetSegment streetSegment, LegMode mode, int endVertexStopIndex) {
        if (egress == null) {
            egress = new ArrayList<>();
        }
        ModeStopIndex modeStopIndex = new ModeStopIndex(mode, endVertexStopIndex);
        int egressIndex;
        if (!egressIndexes.containsKey(modeStopIndex)) {
            egress.add(streetSegment);
            egressIndex = (egress.size() - 1);
            egressIndexes.put(modeStopIndex, egressIndex);
        } else {
            egressIndex = egressIndexes.get(modeStopIndex);
        }
        return egressIndex;
    }

    void addItinerary(Integer accessIdx, Integer egressIdx,
        List<TransitJourneyID> transitJourneyIDs, ZoneId timeZone) {
        Itinerary itinerary = new Itinerary();
        itinerary.transfers = transitJourneyIDs.size() - 1;

        itinerary.walkTime = access.get(accessIdx).duration+egress.get(egressIdx).duration;
        itinerary.distance = access.get(accessIdx).distance+egress.get(egressIdx).distance;
        ZonedDateTime transitStart = transit.get(0).segmentPatterns.get(transitJourneyIDs.get(0).pattern).fromDepartureTime.get(transitJourneyIDs.get(0).time);
        itinerary.startTime = transitStart.minusSeconds(access.get(accessIdx).duration);
        int lastTransit = transitJourneyIDs.size()-1;
        ZonedDateTime transitStop = transit.get(lastTransit).segmentPatterns.get(transitJourneyIDs.get(lastTransit).pattern).toArrivalTime.get(transitJourneyIDs.get(lastTransit).time);
        itinerary.endTime = transitStop.plusSeconds(egress.get(egressIdx).duration);
        itinerary.duration = (int) Duration.between(itinerary.startTime,itinerary.endTime).getSeconds();

        itinerary.transitTime = 0;
        int transitJourneyIDIdx=0;
        for(TransitJourneyID transitJourneyID: transitJourneyIDs) {
            itinerary.transitTime += transit.get(transitJourneyIDIdx).getTransitTime(transitJourneyID);
            transitJourneyIDIdx++;
        }
        itinerary.waitingTime=itinerary.duration-(itinerary.transitTime+itinerary.walkTime);
        PointToPointConnection pointToPointConnection = new PointToPointConnection(accessIdx, egressIdx, transitJourneyIDs);
        itinerary.addConnection(pointToPointConnection);
        this.itinerary.add(itinerary);

    }

    /**
     * Returns index of this acces path or -1 if it isn't in list yet
     * @param mode
     * @param startVertexStopIndex
     * @return
     */
    public int getAccessIndex(LegMode mode, int startVertexStopIndex) {
        return accessIndexes.getOrDefault(new ModeStopIndex(mode, startVertexStopIndex), -1);
    }

    /**
     * Returns index of this egress path or -1 if it isn't in list yet
     * @param mode
     * @param endVertexStopIndex
     * @return
     */
    public int getEgressIndex(LegMode mode, int endVertexStopIndex) {
        return egressIndexes.getOrDefault(new ModeStopIndex(mode, endVertexStopIndex), -1);
    }

    /**
     * Creates itineraries for all access index and egress index combinations
     * @param transitJourneyIDs
     * @param timeZone
     */
    public void addItineraries(List<TransitJourneyID> transitJourneyIDs, ZoneId timeZone) {
        for(Integer accessIdx:accessIndexes.values()) {
            for (Integer egressIdx: egressIndexes.values()) {
                addItinerary(accessIdx, egressIdx, transitJourneyIDs, timeZone);
            }
        }
    }

    /**
     * Adds street path between stops when transfering
     *
     * Also updates walkTime and distance on all itineraries with middle distance and duration
     * @param streetSegment on streetLayer
     * @param transfer object which is used to get which transitSegment is used
     */
    public void addMiddle(StreetSegment streetSegment, Transfer transfer) {
        TransitSegment transitSegment = transit.get(transfer.transitSegmentIndex);
        transitSegment.addMiddle(streetSegment);
        for(Itinerary currentItinerary: this.itinerary) {
            //Call to function is needed because we also need to update waitingTime
            // which is easier if everything is at one place
            currentItinerary.addWalkTime(streetSegment.duration);
            currentItinerary.distance += streetSegment.distance;
        }
    }

    /** recompute wait and ride stats for all transit itineraries, should be done once all transit paths are added */
    public void recomputeStats (ProfileRequest req) {
        if (!fullItineraries.isEmpty()) {
            StatsCalculator.StatsCollection collection =
                    StatsCalculator.computeStatistics(
                            req,
                            // TODO is this intended to handle multiple access modes to the same stop?
                            // do these ever have more or less than one value?
                            access.get(itinerary.get(0).connection.access).duration,
                            egress.get(itinerary.get(0).connection.egress).duration,
                            transit.size(),
                            fullItineraries);

            this.stats = collection.stats;

            for (int leg = 0; leg < transit.size(); leg++) {
                TransitSegment segment = transit.get(leg);
                segment.rideStats = collection.rideStats[leg];
                segment.waitStats = collection.waitStats[leg];
            }
        }
    }

    public List<Fare> getFares() {
        return fares == null ? null : fares.stream().collect(Collectors.toList());
    }
}
