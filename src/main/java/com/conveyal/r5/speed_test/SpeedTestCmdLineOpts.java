package com.conveyal.r5.speed_test;

import org.apache.commons.cli.Options;

import java.util.Arrays;

public class SpeedTestCmdLineOpts extends CommandLineOpts {

    private static final String PRINT_ITINERARIES_OPT = "p";
    private static final String NUM_OF_ITINERARIES_OPT = "i";
    private static final String SEARCH_WINDOW_IN_MINUTES_OPT = "t";
    private static final String SAMPLE_TEST_N_TIMES = "n";
    private static final String PROFILES = "o";

    SpeedTestCmdLineOpts(String[] args) {
        super(args);
    }

    @Override
    Options speedTestOptions() {
        Options options = super.speedTestOptions();
        options.addOption(NUM_OF_ITINERARIES_OPT, "numOfItineraries", true, "Number of itineraries to return");
        options.addOption(PRINT_ITINERARIES_OPT, "printItineraries", false, "Print itineraries found");
        options.addOption(SEARCH_WINDOW_IN_MINUTES_OPT, "searchTimeWindowInMinutes", true, "The time in minutes to add to from to time");
        options.addOption(SAMPLE_TEST_N_TIMES, "sampleTestNTimes", true, "Repeat the test N times. Profiles are altered in a round robin fashion.");
        options.addOption(PROFILES, "profiles", true, "A coma separated list of configuration profiles: " + Arrays.toString(ProfileFactory.values()));
        return options;
    }

    boolean printItineraries() {
        return cmd.hasOption(PRINT_ITINERARIES_OPT);
    }

    int numOfItineraries() {
        return Integer.valueOf(cmd.getOptionValue(NUM_OF_ITINERARIES_OPT, "3"));
    }

    int searchWindowInMinutes() {
        return Integer.valueOf(cmd.getOptionValue(SEARCH_WINDOW_IN_MINUTES_OPT, "60"));
    }

    int numberOfTestsSamplesToRun() {
        return Integer.valueOf(cmd.getOptionValue(SAMPLE_TEST_N_TIMES, Integer.toString(profiles().length)));
    }

    ProfileFactory[] profiles() {
        return cmd.hasOption(PROFILES) ? ProfileFactory.parse(cmd.getOptionValue(PROFILES)) : ProfileFactory.values();
    }
}
