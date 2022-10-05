package com.conveyal.r5.streets;

/**
 * Edge flags are various boolean values (requiring a single bit of information) that can be attached to each
 * edge in the street graph. They are each assigned a bit number from 0...31 so they can all be packed into a
 * single integer field for space and speed reasons.
 */
public enum EdgeFlag {

    // Street categories.
    // FIXME some street categories are mutually exclusive and should not be flags, just narrow numbers.
    // This could be done with static utility functions that write the low N bits of a number into a given position.
    // Maybe reserve the first 4-5 bits (or a whole byte, and 16 bits for flags) for mutually exclusive edge types.
    UNUSED(0), // This flag is deprecated and currently unused. Use it for something new and interesting!
    BIKE_PATH(1),
    SIDEWALK(2),
    CROSSING(3),
    ROUNDABOUT(4),
    ELEVATOR(5),
    STAIRS(6),
    PLATFORM(7),
    BOGUS_NAME(8),
    NO_THRU_TRAFFIC(9),
    NO_THRU_TRAFFIC_PEDESTRIAN(10),
    NO_THRU_TRAFFIC_BIKE(11),
    NO_THRU_TRAFFIC_CAR(12),
    SLOPE_OVERRIDE(13),
    /**
     * An edge that links a transit stop to the street network; two such edges should not be traversed consecutively.
     */
    LINK(14),

    // Permissions
    ALLOWS_PEDESTRIAN(15),
    ALLOWS_BIKE(16),
    ALLOWS_CAR(17),
    ALLOWS_WHEELCHAIR(18),
    //Set when OSM tags are wheelchair==limited currently unroutable
    LIMITED_WHEELCHAIR(19),

    // If this flag is present, the edge is good idea to use for linking. Excludes runnels, motorways, and covered roads.
    LINKABLE(20),

    // The highest five bits of the flags field represent the bicycle level of traffic stress (LTS) for this street.
    // See http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
    // The comments below on the LTS flag for each level 1 through 4 are pasted from that document.
    // FIXME bicycle LTS should not really be flags, the categories are mutually exclusive and can be stored in 2 bits.

    /**
     * If this flag is set, then its LTS has been loaded from OSM tags and we will not apply further processing to
     * infer it. We jump to flag number 27 here to group all five LTS flags together at the high end of the 32-bit
     * integer flags field.
     */
    BIKE_LTS_EXPLICIT(27),

    /**
     * Presenting little traffic stress and demanding little attention from cyclists, and attractive enough for a
     * relaxing bike ride. Suitable for almost all cyclists, including children trained to safely cross intersections.
     * On links, cyclists are either physically separated from traffic, or are in an exclusive bicycling zone next to
     * a slow traffic stream with no more than one lane per direction, or are on a shared road where they interact
     * with only occasional motor vehicles (as opposed to a stream of traffic) with a low speed differential. Where
     * cyclists ride alongside a parking lane, they have ample operating space outside the zone into which car
     * doors are opened. Intersections are easy to approach and cross.
     */
    BIKE_LTS_1(28),

    /**
     * Presenting little traffic stress and therefore suitable to most adult cyclists but demanding more attention
     * than might be expected from children. On links, cyclists are either physically separated from traffic, or are
     * in an exclusive bicycling zone next to a well-confined traffic stream with adequate clearance from a parking
     * lane, or are on a shared road where they interact with only occasional motor vehicles (as opposed to a
     * stream of traffic) with a low speed differential. Where a bike lane lies between a through lane and a rightturn
     * lane, it is configured to give cyclists unambiguous priority where cars cross the bike lane and to keep
     * car speed in the right-turn lane comparable to bicycling speeds. Crossings are not difficult for most adults.
     */
    BIKE_LTS_2(29),

    /**
     * More traffic stress than LTS 2, yet markedly less than the stress of integrating with multilane traffic, and
     * therefore welcome to many people currently riding bikes in American cities. Offering cyclists either an
     * exclusive riding zone (lane) next to moderate-speed traffic or shared lanes on streets that are not multilane
     * and have moderately low speed. Crossings may be longer or across higher-speed roads than allowed by
     * LTS 2, but are still considered acceptably safe to most adult pedestrians.
     */
    BIKE_LTS_3(30),

    /**
     * A level of stress beyond LTS3. (this is in fact the official definition. -Ed.)
     * Also known as FLORIDA_AVENUE.
     */
    BIKE_LTS_4(31);

    /**
     * In each enum value this field should contain an integer with only a single bit switched on (a power of two).
     */
    public final int flag;

    /**
     * Conveniently create a unique integer flag pattern for each of the enum values.
     */
    private EdgeFlag(int bitNumber) {
        flag = 1 << bitNumber;
    }

}
