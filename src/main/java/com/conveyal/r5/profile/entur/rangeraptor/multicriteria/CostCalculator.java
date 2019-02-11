package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.request.MultiCriteriaCostFactors;

class CostCalculator {
    private static final int PRECISION = 100;
    private final int boardCost;
    private final int walkFactor;
    private final int waitFactor;
    private final int transitFactor;


    CostCalculator(MultiCriteriaCostFactors requestParams) {
        this.boardCost = PRECISION * requestParams.boardCost();
        this.walkFactor = (int) (PRECISION * requestParams.walkReluctanceFactor());
        this.waitFactor = (int) (PRECISION * requestParams.waitReluctanceFactor());
        this.transitFactor = PRECISION;
    }

    int transitArrivalCost(int prevStopArrivalTime, int boardTime, int alightTime) {
        int waitTime = boardTime - prevStopArrivalTime;
        int transitTime = alightTime - boardTime;
        return waitFactor * waitTime + transitFactor * transitTime + boardCost;
    }

    int walkCost(int walkTimeInSeconds) {
        return walkFactor * walkTimeInSeconds;
    }
}
