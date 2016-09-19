package com.conveyal.r5.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
*
*/
public enum PickDropType {

    SCHEDULED(0),
    NONE(1),
    CALL_AGENCY(2),
    COORDINATE_WITH_DRIVER(3);

    private static final Logger LOG = LoggerFactory.getLogger(PickDropType.class);

    // Will be initialized after constructor is called on all enum values.
    private static PickDropType[] forGtfsCode;

    static {
        forGtfsCode = new PickDropType[4];
        for (PickDropType pdt : PickDropType.values()) {
            forGtfsCode[pdt.gtfsCode] = pdt;
        }
    }

    int gtfsCode;

    PickDropType(int gtfsCode) {
        this.gtfsCode = gtfsCode;
    }

    static PickDropType forGtfsCode (int gtfsCode) {
        if (gtfsCode >= forGtfsCode.length) {
            LOG.error("Pickup/dropoff code {} is invalid. Defaulting to 0 ('scheduled')", gtfsCode);
            gtfsCode = 0;
        }
        return forGtfsCode[gtfsCode];
    }

}
