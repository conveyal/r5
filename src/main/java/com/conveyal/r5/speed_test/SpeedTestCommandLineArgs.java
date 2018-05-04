package com.conveyal.r5.speed_test;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;

class SpeedTestCommandLineArgs {
    private CommandLine cmd;
    private static final String ROOT_DIR_OPT = "d";
    private static final String MULTI_CRITERIA_RR_OPT = "c";

    SpeedTestCommandLineArgs(String[] args) {
        Options options = new Options();
        options.addOption(ROOT_DIR_OPT, "rootDir", true, "Root directory where network and input files are located. (Optional)");
        options.addOption(MULTI_CRITERIA_RR_OPT, "multiCriteria", false, "Use multi criteria version of range raptor. (Optional)");

        CommandLineParser cmdParser = new DefaultParser();


        try {
            cmd = cmdParser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            HelpFormatter formater = new HelpFormatter();
            formater.printHelp("SpeedTest", options);
            System.exit(-1);
        }
    }

    File rootDir() {
        File rootDir = new File(cmd.getOptionValue(ROOT_DIR_OPT, "."));
        if(!rootDir.exists()) {

        }
        return rootDir;
    }

    boolean useMultiCriteriaSearch() {
        return cmd.hasOption(MULTI_CRITERIA_RR_OPT);
    }
}
