package com.conveyal.r5;

import com.conveyal.r5.analyst.broker.BrokerMain;
import com.conveyal.r5.analyst.cluster.AnalystWorker;

import java.util.Arrays;

/**
 * Main entry point for R5.
 */
public class R5Main {
    public static void main (String... args) {
        String command = args[0];

        String[] cargs = new String[args.length - 1];
        System.arraycopy(args, 1, cargs, 0, cargs.length);

        if ("broker".equals(command)) {
            BrokerMain.main(cargs);
        } else if ("worker".equals(command)) {
            AnalystWorker.main(cargs);
        }
    }
}
