package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;

/**
 * Represents a TAUI project
 */
public class Project extends Model implements Cloneable {
    /** Names of the variants of this project */
    public String[] variants;

    public String regionId;

    public String bundleId;

    public AnalysisRequest analysisRequestSettings;

    public Project clone () {
        try {
            return (Project) super.clone();
        } catch (CloneNotSupportedException e) {
            throw AnalysisServerException.unknown(e);
        }
    }
}
