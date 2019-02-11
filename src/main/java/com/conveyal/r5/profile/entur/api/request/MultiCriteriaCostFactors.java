package com.conveyal.r5.profile.entur.api.request;


/**
 * This class define how to calculate the cost when cost is part of the multi-criteria pareto function.
 * <p/>
 */
public class MultiCriteriaCostFactors {
    static final MultiCriteriaCostFactors DEFAULTS = new MultiCriteriaCostFactors();

    private final int boardCost;
    private final int walkReluctanceFactor;
    private final int waitReluctanceFactor;

    /**
     * Default constructor defines default values.
     */
    private MultiCriteriaCostFactors() {
        this.boardCost = 300;
        this.walkReluctanceFactor = 200;
        this.waitReluctanceFactor = 100;
    }

    MultiCriteriaCostFactors(RequestBuilder<?> builder) {
        this.boardCost = builder.multiCriteriaBoardCost();
        this.walkReluctanceFactor = builder.multiCriteriaWalkReluctanceFactor();
        this.waitReluctanceFactor = builder.multiCriteriaWaitReluctanceFactor();
    }

    public int boardCost() {
        return boardCost;
    }

    /**
     * A walk reluctance factor of 100 regarded as neutral. 400 means the rider
     * would rater sit 4 minutes extra on a buss, than walk 1 minute extra.
     */
    public int walkReluctanceFactor() {
        return walkReluctanceFactor;
    }


    public int waitReluctanceFactor() {
        return waitReluctanceFactor;
    }
}
