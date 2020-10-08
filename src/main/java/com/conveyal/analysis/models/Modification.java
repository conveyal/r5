package com.conveyal.analysis.models;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * We could add defaultImpl = CustomModificationHolder.class, which might allow us to eliminate having separate type vs.
 * r5type properties in custom backend modifications. But we'd need to define a TypeResolver that would serialize
 * arbitrary type codes unknown to the backend.
 *
 * Created by matthewc on 2/9/16.
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include= JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "add-trip-pattern", value = AddTripPattern.class),
        @JsonSubTypes.Type(name = "remove-trips", value = RemoveTrips.class),
        @JsonSubTypes.Type(name = "remove-stops", value = RemoveStops.class),
        @JsonSubTypes.Type(name = "adjust-speed", value = AdjustSpeed.class),
        @JsonSubTypes.Type(name = "adjust-dwell-time", value = AdjustDwellTime.class),
        @JsonSubTypes.Type(name = "convert-to-frequency", value = ConvertToFrequency.class),
        @JsonSubTypes.Type(name = "reroute", value = Reroute.class),
        @JsonSubTypes.Type(name = "custom", value = CustomModificationHolder.class),
        @JsonSubTypes.Type(name = "add-streets", value = AddStreets.class),
        @JsonSubTypes.Type(name = "modify-streets", value = ModifyStreets.class)
})
public abstract class Modification extends Model implements Cloneable {
    /** the type of this modification, see JsonSubTypes annotation above */
    public abstract String getType ();

    public Modification clone () throws CloneNotSupportedException {
        return (Modification) super.clone();
    }

    /** What project is this modification a part of? */
    public String projectId;

    /** what variants is this modification a part of? */
    public boolean[] variants;

    /** A description/comment about this modification */
    public String description;

    /**
     * Add scope to each ID in an array of IDs. Used in converting internal analysis-backend modification types to R5
     * modifications sent to the workers. See feedScopeId() for more details.
     *
     * This preserves null arrays coming in from MongoDB (in the internal analysis-backend modification types) because
     * in R5 modifications, the null set has a distinct meaning from an empty set (null matches everything, empty set
     * matches nothing). TODO We should probably disallow and fail on empty sets though, since they must be mistakes.
     */
    public Set<String> feedScopeIds (String feed, String[] ids) {
        if (ids == null) {
            return null;
        } else {
            return Arrays.stream(ids).map(id -> feedScopeId(feed, id)).collect(Collectors.toSet());
        }
    }

    /**
     * Combine a feed ID and an entity ID into a single ID string that will be unique within an R5 TransportNetwork.
     * A modification can only refer to entities from one specific feed. UI/backend modifications have a "feed" field
     * with the ID of the feed uniquely identifying it within a Bundle. R5 (worker) modifications do not have this feed
     * field, so every individual entity reference has to be prepended with a feed scope.
     */
    public String feedScopeId (String feed, String id) {
        return String.format("%s:%s", feed, id);
    }

    public abstract com.conveyal.r5.analyst.scenario.Modification toR5 ();
}
