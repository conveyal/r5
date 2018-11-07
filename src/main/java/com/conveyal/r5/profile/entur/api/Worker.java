package com.conveyal.r5.profile.entur.api;


import java.util.Collection;

public interface Worker {
    Collection<? extends Path2> route();
}
