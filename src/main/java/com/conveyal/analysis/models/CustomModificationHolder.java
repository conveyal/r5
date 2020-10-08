package com.conveyal.analysis.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
import java.util.Map;

/**
 * This is the UI/Backend model for a freeform JSON modification.
 * It uses the JsonAnyGetter and JsonAnySetter annotations to handle all unrecognized properties, i.e. anything
 * that does not map to a field on the base class.
 */
public class CustomModificationHolder extends Modification {

    public String getType() {
        return "custom";
    }

    private Map<String, Object> freeformProperties = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getFreeformProperties() {
        return freeformProperties;
    }

    @JsonAnySetter
    public void setFreeformProperties(String key, Object value) {
        this.freeformProperties.put(key, value);
    }

    public com.conveyal.r5.analyst.scenario.CustomModificationHolder toR5 () {
        com.conveyal.r5.analyst.scenario.CustomModificationHolder customR5 =
                new com.conveyal.r5.analyst.scenario.CustomModificationHolder(freeformProperties, name);
        return customR5;
    }

}
