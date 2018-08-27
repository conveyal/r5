package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;

import java.util.Collection;

public interface Worker {
    Collection<Path> route();
}
