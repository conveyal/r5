package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.components.WorkerComponents;

import java.util.Arrays;

/**
 * Old R5 JARs before December 2020 had main-class set to R5Main, which used the first command line parameter to
 * select between starting an analysis worker and a point-to-point routing server. We no longer use the latter option,
 * but we want to allow the backend to start all worker versions in exactly the same way, so we ignore the command line
 * parameters. If we ever want to allow other roles, we can check that this parameter equals 'worker'.
 */
public class WorkerMain {

    public static void main (String[] args) {
        System.out.println("=== Conveyal R5 ===");
        String[] expectedArgs = new String[]{"worker", "worker.conf"};
        if (!Arrays.equals(args, expectedArgs)) {
            System.out.println("This method must always be called with the parameters:" + expectedArgs);
            System.exit(1);
        }
        // TODO consider the fact that workers are started two different ways:
        // AnalysisWorker.main() calls AnalysisWorker.forConfig(config).run() [which should actually be in cluster repo]
        // but also LocalWorkerLauncher.launch calls new AnalysisWorker(singleWorkerConfig, fileStorage, transportNetworkCache);
        WorkerComponents components = new WorkerComponents();
        components.analysisWorker.run(); // Any reason to start in another thread?
    }

}
