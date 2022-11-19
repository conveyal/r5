package com.conveyal.analysis.models;

import com.conveyal.r5.profile.StreetMode;
import com.google.common.primitives.Doubles;

import java.util.List;
import java.util.Set;

/**
 * Fields replicated from the R5 ModifyStreets modification.
 */
public class ModifyStreets extends Modification {

    public List<List<List<Double>>> polygons;
    public Set<StreetMode> allowedModes;
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

        mod.polygons = (double[][][]) polygons.stream().map(p -> p.stream().map(Doubles::toArray).toArray()).toArray();
        mod.allowedModes = allowedModes;
        mod.carSpeedKph = carSpeedKph;
        mod.walkTimeFactor = walkTimeFactor;
        mod.bikeTimeFactor = bikeTimeFactor;
        mod.bikeLts = bikeLts;

        return mod;
    }
}
