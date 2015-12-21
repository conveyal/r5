package com.conveyal.r5.api.util;

import java.util.List;

/**
 * Created by mabu on 21.12.2015.
 */
public class PointToPointConnection {
    //Index of access part of this trip
    public int access;
    //Index of egress part of this trip
    public int egress;
    /*chooses which specific trip should be used
    Index in transit list specifies transit with same index
    Each TransitJourneyID has pattern in chosen index an time index in chosen pattern

    This can uniquly identify specific trip with transit */
    public List<TransitJourneyID> transit;
}
