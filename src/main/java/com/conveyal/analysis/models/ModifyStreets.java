package com.conveyal.analysis.models;

import com.conveyal.r5.profile.StreetMode;

import java.util.EnumSet;

/**
 * Fields replicated from the R5 ModifyStreets modification.
 */
public class ModifyStreets extends Modification {

    public double[][][] polygons;
    public EnumSet<StreetMode> allowedModes;
    public Double carSpeedKph;
    public Double walkTimeFactor;
    public Double bikeTimeFactor;
    public Integer bikeLts;

    @Override
    public String getType() {
        return "modify-streets";
    }

    @Override
    public com.conveyal.r5.analyst.scenario.Modification toR5() {
        com.conveyal.r5.analyst.scenario.ModifyStreets mod = new com.conveyal.r5.analyst.scenario.ModifyStreets();
        mod.comment = name;

        mod.polygons = polygons;
        mod.allowedModes = allowedModes;
        mod.carSpeedKph = carSpeedKph;
        mod.walkTimeFactor = walkTimeFactor;
        mod.bikeTimeFactor = bikeTimeFactor;
        mod.bikeLts = bikeLts;

        return mod;
    }
}
