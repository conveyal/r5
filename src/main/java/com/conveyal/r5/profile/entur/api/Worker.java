package com.conveyal.r5.profile.entur.api;


import java.util.Collection;

public interface Worker<P> {
    Collection<P> route(RangeRaptorRequest request);
}
