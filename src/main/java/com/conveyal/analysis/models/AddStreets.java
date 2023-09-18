package com.conveyal.analysis.models;

import com.conveyal.r5.profile.StreetMode;
import com.google.common.primitives.Doubles;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;

import java.util.List;
import java.util.Set;

/**
 * Fields replicated from the R5 AddStreets modification.
 */
@BsonDiscriminator(key = "type", value = "add-streets")
public class AddStreets extends Modification {
    public List<List<List<Double>>> lineStrings;
    public Set<StreetMode> allowedModes;
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

        mod.lineStrings = (double[][][]) lineStrings.stream().map(ls -> ls.stream().map(Doubles::toArray).toArray()).toArray();
        mod.allowedModes = allowedModes;
        mod.carSpeedKph = carSpeedKph;
        mod.walkTimeFactor = walkTimeFactor;
        mod.bikeTimeFactor = bikeTimeFactor;
        mod.bikeLts = bikeLts;
        mod.linkable = linkable;

        return mod;
    }
}
