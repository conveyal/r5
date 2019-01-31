package com.conveyal.r5.speed_test;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

class CommandLineOpts {
    private static final boolean OPTION_UNKNOWN_THEN_FAIL = false;
    protected CommandLine cmd;

    /* Shared options */
    private static final String ROOT_DIR_OPT = "d";
    private static final String HELP_OPT = "h";

    /* Speed test options - defined here to keep all option together */
    static final String VERBOSE_OPT = "v";
    static final String NUM_OF_ITINERARIES_OPT = "i";
    static final String SEARCH_WINDOW_IN_MINUTES_OPT = "m";
    static final String SAMPLE_TEST_N_TIMES_OPT = "n";
    static final String PROFILES_OPT = "p";
    static final String TEST_CASES_OPT = "c";
    static final String DEBUG_STOPS = "s";
    static final String DEBUG_TRIP = "t";
    static final String DEBUG = "D";


    CommandLineOpts(String[] args) {
        Options options = speedTestOptions();

        CommandLineParser cmdParser = new DefaultParser();

        try {
            cmd = cmdParser.parse(options, args, OPTION_UNKNOWN_THEN_FAIL);

            if(printHelpOptSet()) {
                printHelp(options);
                System.exit(0);
            }
            if(!cmd.getArgList().isEmpty()) {
                System.err.println("Unexpected argument(s): " + cmd.getArgList());
                printHelp(options);
                System.exit(-2);
            }

        } catch (ParseException e) {
            System.err.println(e.getMessage());
            printHelp(options);
            System.exit(-1);
        }
    }

    Options speedTestOptions() {
        Options options = new Options();
        options.addOption(ROOT_DIR_OPT, "dir", true, "The directory where network and input files are located. (Optional)");
        options.addOption(HELP_OPT, "help", false, "Print all command line options, then exit. (Optional)");
        options.addOption(DEBUG_STOPS, "debugStops", true, "A coma separated list of stops to debug.");
        options.addOption(DEBUG_TRIP, "debugTrip", true, "A coma separated list of stops representing a trip/path to debug. " +
                "Use a '*' to indicate where to start debugging. For example '1,*2,3' will print event at stop 2 and 3, " +
                "but not stop 1 for all trips starting with the given stop sequence.");
        options.addOption(DEBUG, "debug", false, "Enable debug info.");
        return options;
    }

    File rootDir() {
        File rootDir = new File(cmd.getOptionValue(ROOT_DIR_OPT, "."));
        if(!rootDir.exists()) {
            throw new IllegalArgumentException("Unable to find root directory: " + rootDir.getAbsolutePath());
        }
        return rootDir;
    }

    public boolean debug() {
        return cmd.hasOption(DEBUG);
    }

    List<Integer> debugStops() {
        return parseCSVToInt(DEBUG_STOPS);
    }

    List<Integer> debugTrip() {
        return parseCSVList(DEBUG_TRIP).stream()
                .map(it -> it.startsWith("*") ? it.substring(1) : it)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    int debugTripAtStopIndex() {
        List<String> stops = parseCSVList(DEBUG_TRIP);
        for (int i = 0; i < stops.size(); ++i) {
            if (stops.get(i).startsWith("*")) return i;
        }
        return 0;
    }

    private List<Integer> parseCSVToInt(String opt) {
        return cmd.hasOption(opt)
                ? parseCSVList(opt).stream().map(Integer::valueOf).collect(Collectors.toList())
                : Collections.emptyList();
    }

    List<String> parseCSVList(String opt) {
        return cmd.hasOption(opt)
                ? Arrays.asList(cmd.getOptionValue(opt).split("\\s*,\\s*"))
                : Collections.emptyList();
    }

    private boolean printHelpOptSet() {
        return cmd.hasOption(HELP_OPT);
    }

    private void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(140);
        formatter.printHelp("[options]", options);
    }
}
