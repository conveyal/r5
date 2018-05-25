package com.conveyal.r5.speed_test;

import org.apache.commons.cli.Options;

public class SpeedTestCmdLineOpts extends CommandLineOpts {

    private static final String PRINT_ITINERARIES_OPT = "p";
    private static final String NUM_OF_ITINERARIES_OPT = "i";
    private static final String SEARCH_WINDOW_IN_MINUTES_OPT = "t";

    SpeedTestCmdLineOpts(String[] args) {
        super(args);
    }

    @Override
    Options speedTestOptions() {
        Options options = super.speedTestOptions();
        options.addOption(NUM_OF_ITINERARIES_OPT, "numOfItineraries", true, "Number of itineraries to return");
        options.addOption(PRINT_ITINERARIES_OPT, "printItineraries", false, "Print itineraries found");
        options.addOption(SEARCH_WINDOW_IN_MINUTES_OPT, "searchTimeWindowInMinutes", true, "The time in minutes to add to from to time");
        return options;
    }

    boolean printItineraries() {
        return cmd.hasOption(PRINT_ITINERARIES_OPT);
    }

    int numOfItineraries() {
        return Integer.valueOf(cmd.getOptionValue(NUM_OF_ITINERARIES_OPT, "3"));
    }

    public int searchWindowInMinutes() {
        return Integer.valueOf(cmd.getOptionValue(SEARCH_WINDOW_IN_MINUTES_OPT, "60"));
    }
}
