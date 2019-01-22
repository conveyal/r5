package com.conveyal.r5.profile.entur.rangeraptor.transit;

abstract class AbstractTransitCalculator implements TransitCalculator {
    private final int boardSlackInSeconds;
    private final int maxTimeLimit;

    AbstractTransitCalculator(int boardSlackInSeconds, int maxTimeLimit) {
        this.boardSlackInSeconds = boardSlackInSeconds;
        this.maxTimeLimit = maxTimeLimit;
    }

    @Override
    public int maxTimeLimit() {
        return maxTimeLimit;
    }

    @Override
    public final int addBoardSlack(int time) {
        return add(time, boardSlackInSeconds);
    }

    @Override
    public final int subBoardSlack(int time) {
        return sub(time, boardSlackInSeconds);
    }

    @Override
    public final int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return sub(subBoardSlack(firstTransitBoardTime), accessLegDuration);
    }
}
