package com.conveyal.r5.speed_test;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;

class CommandLineOpts {
    private static final boolean OPTION_UNKNOWN_THEN_FAIL = false;
    protected CommandLine cmd;

    /* Shared options */
    private static final String ROOT_DIR_OPT = "d";
    private static final String ORIGINAL_CODE = "o";
    private static final String HELP_OPT = "h";

    /* Speed test options - defined here to keep all option together */
    static final String VERBOSE_OPT = "v";
    static final String NUM_OF_ITINERARIES_OPT = "i";
    static final String SEARCH_WINDOW_IN_MINUTES_OPT = "t";
    static final String SAMPLE_TEST_N_TIMES_OPT = "n";
    static final String PROFILES_OPT = "p";
    static final String TEST_CASES_OPT = "c";
    static final String DEBUG_STOPS = "s";


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
        options.addOption(ROOT_DIR_OPT, "rootDir", true, "Root directory where network and input files are located. (Optional)");
        options.addOption(ORIGINAL_CODE, "originalCode", false, "Use the original range raptor code. (Optional)");
        options.addOption(HELP_OPT, "help", false, "Print all command line options, then exit. (Optional)");
        return options;
    }

    File rootDir() {
        File rootDir = new File(cmd.getOptionValue(ROOT_DIR_OPT, "."));
        if(!rootDir.exists()) {
            throw new IllegalArgumentException("Unable to find root directory: " + rootDir.getAbsolutePath());
        }
        return rootDir;
    }

    boolean useOriginalCode() {
        return cmd.hasOption(ORIGINAL_CODE);
    }

    private boolean printHelpOptSet() {
        return cmd.hasOption(HELP_OPT);
    }

    private void printHelp(Options options) {
        HelpFormatter formater = new HelpFormatter();
        formater.printHelp("[options]", options);
    }
}
