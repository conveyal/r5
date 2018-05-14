package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.Path;

import java.util.List;

class BestResult {
    final int totalTime;
    final int rangeIndex;
    final int stopIndex;
    final Path transitPath;

    BestResult(int totalTime, int rangeIndex, int stopIndex, Path transitPath) {
        this.totalTime = totalTime;
        this.rangeIndex = rangeIndex;
        this.stopIndex = stopIndex;
        this.transitPath = transitPath;
    }

    boolean fasterThen(int totalTime) {
        return this.totalTime <= totalTime;
    }

    public Path path(List<Path[]> pathsPerIteration) {
        return pathsPerIteration.get(rangeIndex)[stopIndex];
    }
}
