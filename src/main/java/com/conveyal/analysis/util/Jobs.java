package com.conveyal.analysis.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Contains a shared ExecutorService.
 */
public class Jobs {
    public static ExecutorService service = Executors.newCachedThreadPool();
}
