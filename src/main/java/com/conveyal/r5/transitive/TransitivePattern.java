package com.conveyal.r5.transitive;

import java.util.List;

/**
 * Represents a transitive pattern.
 */
public class TransitivePattern {
    public String pattern_id;
    public String pattern_name;
    public String route_id;
    public List<StopIdRef> stops;

    // TODO is this level of indirection necessary?
    public static class StopIdRef {
        public String stop_id;

        public StopIdRef (String stop_id) {
            this.stop_id = stop_id;
        }
    }
}
