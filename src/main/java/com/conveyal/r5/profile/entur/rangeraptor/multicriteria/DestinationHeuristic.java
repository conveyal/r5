package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


public interface DestinationHeuristic {

    int getMinTravelTime();

    int getMinNumTransfers();

    int getMinCost();
}
