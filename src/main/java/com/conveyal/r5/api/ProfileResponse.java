package com.conveyal.r5.api;

import com.conveyal.r5.api.util.ProfileOption;
import com.conveyal.r5.api.util.SegmentPattern;
import com.conveyal.r5.api.util.TransitSegment;

import java.util.*;

/**
 * Created by mabu on 30.10.2015.
 */
public class ProfileResponse {
    public List<ProfileOption> options;

    @Override public String toString() {
        return "ProfileResponse{" +
            "options=" + options +
            '}';
    }

    public List<ProfileOption> getOptions() {
        return options;
    }

    public List<SegmentPattern> getPatterns() {
        Map<String, SegmentPattern> patterns = new HashMap<>(10);

        for (ProfileOption option: options) {
            if (option.transit != null && !option.transit.isEmpty()) {
                for (TransitSegment transitSegment: option.transit) {
                    if (transitSegment.segmentPatterns != null && !transitSegment.segmentPatterns.isEmpty()) {
                        for (SegmentPattern segmentPattern : transitSegment.segmentPatterns) {
                            patterns.put(segmentPattern.patternId, segmentPattern);
                        }
                    }
                }
            }
        }

        //TODO: return as a map since I think it will be more usefull but GraphQL doesn't support map
        return new ArrayList<>(patterns.values());
    }
}
