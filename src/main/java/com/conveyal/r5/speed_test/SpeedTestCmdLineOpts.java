package com.conveyal.r5.speed_test;

import org.apache.commons.cli.Options;

import java.util.List;

public class SpeedTestCmdLineOpts extends CommandLineOpts {

    // For list of options see super class

    SpeedTestCmdLineOpts(String[] args) {
        super(args);
    }

    @Override
    Options speedTestOptions() {
        Options options = super.speedTestOptions();
        options.addOption(NUM_OF_ITINERARIES_OPT, "numOfItineraries", true, "Number of itineraries to return.");
        options.addOption(VERBOSE_OPT, "verbose", false, "Verbose output, print itineraries.");
        options.addOption(SAMPLE_TEST_N_TIMES_OPT, "sampleTestNTimes", true, "Repeat the test N times. Profiles are altered in a round robin fashion.");
        options.addOption(PROFILES_OPT, "profiles", true, "A coma separated list of configuration profiles:\n" + String.join("\n", SpeedTestProfiles.options()));
        options.addOption(TEST_CASES_OPT, "testCases", true, "A coma separated list of test case numbers to run.");
        return options;
    }

    boolean verbose() {
        return cmd.hasOption(VERBOSE_OPT);
    }

    int numOfItineraries() {
        return Integer.valueOf(cmd.getOptionValue(NUM_OF_ITINERARIES_OPT, "3"));
    }

    int numberOfTestsSamplesToRun() {
        return Integer.valueOf(cmd.getOptionValue(SAMPLE_TEST_N_TIMES_OPT, Integer.toString(profiles().length)));
    }

    SpeedTestProfiles[] profiles() {
        return cmd.hasOption(PROFILES_OPT) ? SpeedTestProfiles.parse(cmd.getOptionValue(PROFILES_OPT)) : SpeedTestProfiles.values();
    }

    List<String> testCases() {
        return parseCSVList(TEST_CASES_OPT);
    }
}
