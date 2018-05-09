package com.conveyal.r5.speed_test;


import java.util.ArrayList;
import java.util.List;

public class BestKnownResults {
    final int limit;
    final List<BestResult> results;


    BestKnownResults(int limit) {
        this.limit = limit;
        results = new ArrayList<>(limit+1);
    }


    public void addResult(int totalTime, int rangeIndex, int stopIndex) {
        if(results.size() < limit) {
            results.add(new BestResult(totalTime, rangeIndex, stopIndex));
            sort();
        }
        else if(results.get(0).fasterThen(totalTime)) {
            results.set(0, new BestResult(totalTime, rangeIndex, stopIndex));
            sort();
        }
    }

    private void sort() {
        if(results.size() > 1) {
            results.sort((o1, o2) -> o2.totalTime - o1.totalTime);
        }
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }
}
