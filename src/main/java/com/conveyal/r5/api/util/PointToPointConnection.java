package com.conveyal.r5.api.util;

import java.util.List;

/**
 * Object which pulls together specific access, transit and egress part of an option
 */
public class PointToPointConnection {
    //Index of access part of this trip @notnull
    public int access;
    //Index of egress part of this trip
    public Integer egress;
    /*chooses which specific trip should be used
    Index in transit list specifies transit with same index
    Each TransitJourneyID has pattern in chosen index an time index in chosen pattern

    This can uniquly identify specific trip with transit */
    public List<TransitJourneyID> transit;

    //Connection for transit modes
    public PointToPointConnection(int accessIdx, int egressIdx,
        List<TransitJourneyID> transitJourneyIDs) {
        access = accessIdx;
        egress = egressIdx;
        transit = transitJourneyIDs;
    }

    //Connection for direct modes
    public PointToPointConnection(int accessIndex) {
        access = accessIndex;
        egress = null;
    }
}
