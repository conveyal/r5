package com.conveyal.analysis.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
import java.util.Map;

/// This is the UI/Backend model for a freeform JSON modification. It uses the JsonAnyGetter and
/// JsonAnySetter annotations to handle any properties that do not map to fields on the Modification
/// base class. One of those freeform properties should be 'r5type', which names the R5 modification
/// type the worker should deserialize it as. That type may be one the backend does not know about.
/// This includes experimental modifications known only to special custom workers and obsolete
/// modifications only recognized by old workers.
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
