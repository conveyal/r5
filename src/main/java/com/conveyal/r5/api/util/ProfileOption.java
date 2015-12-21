package com.conveyal.r5.api.util;

import java.util.List;

/**
 * This is a response model class which holds data that will be serialized and returned to the client.
 * It is not used internally in routing.
 */
public class ProfileOption {
    //Transit leg of a journey
    public List<TransitSegment> transit;
    //Part of journey from start to transit (or end) @notnull
    public List<StreetSegment> access;
    //Part of a journey between transit stops (transfers)
    public List<StreetSegment> middle;
    //Part of journey from transit to end @notnull
    public List<StreetSegment> egress;
    //Connects all the trip part to a trip at specific time with specific modes of transportation
    public List<Itinerary> itinerary;
    //Time stats for this part of a journey @notnull
    public Stats stats = new Stats();
    //Text description of this part of a journey @notnull
    public String summary;
    public List<Fare> fares;

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

}
