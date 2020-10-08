package com.conveyal.analysis.models;

import com.conveyal.r5.profile.StreetMode;

import java.util.EnumSet;

/**
 * Fields replicated from the R5 AddStreets modification.
 */
public class AddStreets extends Modification {

    public double[][][] lineStrings;
    public EnumSet<StreetMode> allowedModes;
    public Double carSpeedKph;
    public Double walkTimeFactor;
    public Double bikeTimeFactor;
    public Integer bikeLts;
    public boolean linkable;

    @Override
    public String getType() {
        return "add-streets";
    }

    @Override
    public com.conveyal.r5.analyst.scenario.Modification toR5() {
        com.conveyal.r5.analyst.scenario.AddStreets mod = new com.conveyal.r5.analyst.scenario.AddStreets();
        mod.comment = name;

        mod.lineStrings = lineStrings;
        mod.allowedModes = allowedModes;
        mod.carSpeedKph = carSpeedKph;
        mod.walkTimeFactor = walkTimeFactor;
        mod.bikeTimeFactor = bikeTimeFactor;
        mod.bikeLts = bikeLts;
        mod.linkable = linkable;

        return mod;
    }
}
