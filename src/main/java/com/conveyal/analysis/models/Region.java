package com.conveyal.analysis.models;

import com.conveyal.util.HttpServerRuntimeException;

/**
 * Represents a region.
 */
public class Region extends Model implements Cloneable {
    /** Region description */
    public String description;

    /** Bounds of this region */
    public Bounds bounds;

    public Region clone () {
        try {
            return (Region) super.clone();
        } catch (CloneNotSupportedException e) {
            // can't happen.
            throw HttpServerRuntimeException.unknown(e);
        }
    }
}
