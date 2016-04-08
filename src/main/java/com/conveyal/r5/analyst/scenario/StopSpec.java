package com.conveyal.r5.analyst.scenario;

/**
* This represents either an existing or a new stop in Modifications when creating or inserting stops into routes.
 * If the id already exists, the existing stop is used. If not, a new stop is created.
*/
public class StopSpec {
    public String stopId;
    public String name;
    public double lat;
    public double lon;
}
