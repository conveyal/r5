package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.Path;

public interface PathBuilder {
    Path extractPathForStop(int maxRound, int egressStop);
}
