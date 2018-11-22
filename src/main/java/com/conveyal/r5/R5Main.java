package com.conveyal.r5;

import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.point_to_point.PointToPointRouterServer;

import java.util.Arrays;

/**
 * Main entry point for R5.
 * Currently only supports starting up Analyst components (not plain old journey planning).
 * This will start up either an Analyst worker or a broker depending on the first argument.
 */
public class R5Main {
    public static void main (String... args) throws Exception {
        System.out.println("____/\\\\\\\\\\\\\\\\\\_______/\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\_         \n" +
                " __/\\\\\\///////\\\\\\____\\/\\\\\\///////////__        \n" +
                "  _\\/\\\\\\_____\\/\\\\\\____\\/\\\\\\_____________       \n" +
                "   _\\/\\\\\\\\\\\\\\\\\\\\\\/_____\\/\\\\\\\\\\\\\\\\\\\\\\\\_____     \n" +
                "    _\\/\\\\\\//////\\\\\\_____\\////////////\\\\\\___    \n" +
                "     _\\/\\\\\\____\\//\\\\\\_______________\\//\\\\\\__   \n" +
                "      _\\/\\\\\\_____\\//\\\\\\___/\\\\\\________\\/\\\\\\__  \n" +
                "       _\\/\\\\\\______\\//\\\\\\_\\//\\\\\\\\\\\\\\\\\\\\\\\\\\/___ \n" +
                "        _\\///________\\///___\\/////////////_____\n");

        // Pull argument 0 off as the sub-command,
        // then pass the remaining args (1..n) on to that subcommand.
        String command = args[0];
        String[] commandArguments = Arrays.copyOfRange(args, 1, args.length);
        if ("worker".equals(command)) {
            AnalystWorker.main(commandArguments);
        } else if ("point".equals(command)) {
            PointToPointRouterServer.main(commandArguments);
        } else {
            System.err.println("Unknown command " + command);
        }
    }
}
