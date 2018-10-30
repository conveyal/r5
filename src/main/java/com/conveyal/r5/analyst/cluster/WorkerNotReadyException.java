package com.conveyal.r5.analyst.cluster;

/**
 * This exception is thrown to indicate that a function cannot complete because it's still asynchronously loading
 * data it needs to perform its calculations. It implies that the thrower has already recorded the need for
 * those data and has begun an attempt to prepare them.
 *
 * Created by abyrd on 2018-10-30
 */
public class WorkerNotReadyException extends Exception {

    public WorkerNotReadyException(String message) {
        super(message);
    }

}
