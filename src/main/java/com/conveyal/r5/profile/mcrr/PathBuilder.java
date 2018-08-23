package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;

public interface PathBuilder {
    Path extractPathForStop(int maxRound, int egressStop);
}
