package com.conveyal.gtfs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

public class TestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(TestUtils.class);
    private static final AtomicInteger UNIQUE_ID = new AtomicInteger(0);
    private static String pgUrl = "jdbc:postgresql://localhost/postgres";

    /**
     * Forcefully drops a database even if other users are connected to it.
     *
     * @param dbName
     */
    public static void dropDB(String dbName) {
        // first, terminate all other user sessions
        executeAndClose("SELECT pg_terminate_backend(pg_stat_activity.pid) " +
            "FROM pg_stat_activity " +
            "WHERE pg_stat_activity.datname = '" + dbName + "' " +
            "AND pid <> pg_backend_pid()");
        // drop the db
        executeAndClose("DROP DATABASE " + dbName);
    }

    /**
     * Boilerplate for opening a connection, executing a statement and closing connection.
     *
     * @param statement
     * @return true if everything worked.
     */
    private static boolean executeAndClose(String statement) {
        Connection connection;
        try {
            connection = DriverManager.getConnection(pgUrl);
        } catch (SQLException e) {
            e.printStackTrace();
            LOG.error("Error creating new database!");
            return false;
        }

        try {
            connection.prepareStatement(statement).execute();
        } catch (SQLException e) {
            e.printStackTrace();
            LOG.error("Error creating new database!");
            return false;
        }

        try {
            connection.close();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            LOG.error("Error closing connection!");
            return false;
        }
    }

    /**
     * Generate a new database for isolating a test.
     *
     * @return The name of the name database, or null if creation unsucessful
     */
    public static String generateNewDB() {
        String newDBName = uniqueString();
        if (executeAndClose("CREATE DATABASE " + newDBName)) {
            return newDBName;
        } else {
            return null;
        }
    }

    /**
     * Helper to return the relative path to a test resource file
     *
     * @param fileName
     * @return
     */
    public static String getResourceFileName(String fileName) {
        return "./src/test/resources/" + fileName;
    }

    /**
     * Generate a unique string.  Mostly copied from the uniqueId method of https://github.com/javadev/underscore-java
     */
    public static String uniqueString() {
        return "test_db_" + UNIQUE_ID.incrementAndGet();
    }
}
