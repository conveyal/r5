package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.AsyncLoader;

/**
 * This exception is thrown to indicate that a function cannot complete because it's still asynchronously loading
 * data it needs to perform its calculations. It implies that the thrower has already recorded the need for
 * those data and has begun an attempt to prepare them.
 *
 * Created by abyrd on 2018-10-30
 */
public class WorkerNotReadyException extends Exception {

    public final AsyncLoader.Response<TransportNetwork> asyncResponse;

    public WorkerNotReadyException(AsyncLoader.Response<TransportNetwork> asyncResponse) {
        super(asyncResponse.toString());
        this.asyncResponse = asyncResponse;
    }

    public boolean isError() {
        return asyncResponse.status == AsyncLoader.Status.ERROR;
    }

}
