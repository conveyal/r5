package com.conveyal.r5.speed_test;

import org.apache.commons.cli.Options;

import java.util.Arrays;

public class SpeedTestCmdLineOpts extends CommandLineOpts {

    // For list of options see super class

    SpeedTestCmdLineOpts(String[] args) {
        super(args);
    }

    @Override
    Options speedTestOptions() {
        Options options = super.speedTestOptions();
        options.addOption(NUM_OF_ITINERARIES_OPT, "numOfItineraries", true, "Number of itineraries to return");
        options.addOption(VERBOSE_OPT, "verbose", false, "Verbose output: Print itineraries");
        options.addOption(SEARCH_WINDOW_IN_MINUTES_OPT, "searchTimeWindowInMinutes", true, "The time in minutes to add to from to time");
        options.addOption(SAMPLE_TEST_N_TIMES_OPT, "sampleTestNTimes", true, "Repeat the test N times. Profiles are altered in a round robin fashion.");
        options.addOption(PROFILES_OPT, "profiles", true, "A coma separated list of configuration profiles: " + Arrays.toString(ProfileFactory.values()));
        options.addOption(TEST_CASES_OPT, "testCases", true, "A coma separated list of test case numbers to run.");
        options.addOption(DEBUG_STOPS, "debugStops", true, "A coma separated list of stops to debug.");
        return options;
    }

    boolean verbose() {
        return cmd.hasOption(VERBOSE_OPT);
    }

    int numOfItineraries() {
        return Integer.valueOf(cmd.getOptionValue(NUM_OF_ITINERARIES_OPT, "3"));
    }

    int searchWindowInMinutes() {
        return Integer.valueOf(cmd.getOptionValue(SEARCH_WINDOW_IN_MINUTES_OPT, "60"));
    }

    int numberOfTestsSamplesToRun() {
        return Integer.valueOf(cmd.getOptionValue(SAMPLE_TEST_N_TIMES_OPT, Integer.toString(profiles().length)));
    }

    ProfileFactory[] profiles() {
        return cmd.hasOption(PROFILES_OPT) ? ProfileFactory.parse(cmd.getOptionValue(PROFILES_OPT)) : ProfileFactory.values();
    }

    int[] testCases() {
        return parseCSVToInt(TEST_CASES_OPT);
    }

    int[] debugStops() {
        return parseCSVToInt(DEBUG_STOPS);
    }


    /* private methods */

    private int[] parseCSVToInt(String opt) {
        return cmd.hasOption(opt)
                ? Arrays.stream(cmd.getOptionValue(opt).split(",")).mapToInt(Integer::parseInt).toArray()
                : null;
    }
}
