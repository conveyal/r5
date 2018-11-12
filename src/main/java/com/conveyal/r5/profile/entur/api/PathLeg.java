package com.conveyal.r5.profile.entur.api;

import static com.conveyal.r5.profile.entur.rangeraptor.RRStopArrival.NOT_SET;

/**
 * Represent a leg in a path.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface PathLeg<T extends TripScheduleInfo> {

    /**
     * The stop index where the leg start. Also called depature stop index. Defined
     * for all legs except for the access/first leg.
     */
    default int fromStop() { return NOT_SET; }

    /**
     * The time when the leg start/depart from the start. Required.
     */
    int fromTime();

    /**
     * The stop index where the leg end, also calles arrival stop index. Defined
     * for all legs except for the last/egress leg.
     */
    default int toStop() { return NOT_SET; }

    /**
     * The time when the leg end/arrive at end stop. Required.
     */
    int toTime();

    /**
     * If a transit leg, this reference the trip. If not {@code null}.
     */
    default T trip() { return null; }

    /**
     * @return true if transfer. False for Access, Transit and Egress legs.
     */
    default boolean isTransfer() { return false; }

    /**
     * @return true if transit. False for Access, Transfer and Egress legs.
     */
    default boolean isTransit() { return false; }
}
