package com.conveyal.r5.otp2.api.request;


/**
 * Mutable version of the {@link McCostParams}.
 */
public class McCostParamsBuilder {
    private int boardCost;
    private double walkReluctanceFactor;
    private double waitReluctanceFactor;


    McCostParamsBuilder(McCostParams defaults) {
        this.boardCost = defaults.boardCost();
        this.walkReluctanceFactor = defaults.walkReluctanceFactor();
        this.waitReluctanceFactor = defaults.waitReluctanceFactor();
    }

    public int boardCost() {
        return boardCost;
    }

    public McCostParamsBuilder boardCost(int boardCost) {
        this.boardCost = boardCost;
        return this;
    }

    public double walkReluctanceFactor() {
        return walkReluctanceFactor;
    }

    public McCostParamsBuilder walkReluctanceFactor(double walkReluctanceFactor) {
        this.walkReluctanceFactor = walkReluctanceFactor;
        return this;
    }

    public double waitReluctanceFactor() {
        return waitReluctanceFactor;
    }

    public McCostParamsBuilder waitReluctanceFactor(double waitReluctanceFactor) {
        this.waitReluctanceFactor = waitReluctanceFactor;
        return this;
    }
}
