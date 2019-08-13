package com.conveyal.r5.analyst.progress;

public class ProgressListener {

    private int totalElements;

    private int currentElement;

    private int logFrequency;

    private String description;

    public synchronized void increment () {
        currentElement += 1;
    }

}
