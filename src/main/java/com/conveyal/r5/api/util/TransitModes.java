package com.conveyal.r5.api.util;

/**
 * Types of transit mode transport from GTFS
 */
public enum TransitModes {
    // Air
    AIR,
    // Tram, Streetcar, Light rail. Any light rail or street level system within a metropolitan area.
    TRAM,
    //Subway, Metro. Any underground rail system within a metropolitan area.
    SUBWAY,
    //Rail. Used for intercity or long-distance travel.
    RAIL,
    //Bus. Used for short- and long-distance bus routes.
    BUS,
    //Ferry. Used for short- and long-distance boat service.
    FERRY,
    //Cable car. Used for street-level cable cars where the cable runs beneath the car.
    CABLE_CAR,
    // Gondola, Suspended cable car. Typically used for aerial cable cars where the car is suspended from the cable.
    GONDOLA,
    //Funicular. Any rail system designed for steep inclines.
    FUNICULAR,
    //All modes
    TRANSIT
}
