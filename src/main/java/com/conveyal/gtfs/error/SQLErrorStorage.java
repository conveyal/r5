package com.conveyal.gtfs.error;

import com.conveyal.gtfs.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * This is an abstraction for something that stores GTFS loading and validation errors one by one.
 * Currently there's only one implementation, which uses SQL tables.
 * We used to store the errors in plain old Lists, and could make an alternative implementation to do so.
 * We may need to in order to output JSON reports.
 */
public class SQLErrorStorage {

    private static final Logger LOG = LoggerFactory.getLogger(SQLErrorStorage.class);

    // FIXME should we really be holding on to a single connection from a pool? Look into pooling prepared statements.
    private Connection connection;

    private int errorCount; // This serves as a unique ID, so it must persist across multiple validator runs.

    private PreparedStatement insertError;
    private PreparedStatement insertInfo;

    // A string to prepend to all table names. This is a unique identifier for the particular feed that is being loaded.
    // Should include any dot or other separator. May also be the empty string if you want no prefix added.
    private String tablePrefix;

    // How many errors to insert at a time in a batch, for efficiency.
    private static final long INSERT_BATCH_SIZE = 500;

    public SQLErrorStorage (DataSource dataSource, String tablePrefix, boolean createTables) {
        // TablePrefix should always be internally generated so doesn't need to be sanitized.
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        errorCount = 0;
        try {
            this.connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        if (createTables) createErrorTables();
        else reconnectErrorTables();
        createPreparedStatements();
    }

    public void storeError (NewGTFSError error) {
        try {
            // Insert one row for the error itself
            insertError.setInt(1, errorCount);
            insertError.setString(2, error.errorType.name());
            // Using SetObject to allow null values, do all target DBs support this?
            insertError.setObject(3, error.entityType == null ? null : error.entityType.getSimpleName());
            insertError.setObject(4, error.lineNumber);
            insertError.setObject(5, error.entityId);
            insertError.setObject(6, error.entitySequenceNumber);
            insertError.setObject(7, error.badValue);
            insertError.addBatch();
            // Insert all key-value info pairs for the error
            for (Map.Entry<String, String> entry : error.errorInfo.entrySet()) {
                insertInfo.setInt(1, errorCount);
                insertInfo.setString(2, entry.getKey());
                insertInfo.setString(3, entry.getValue());
                insertInfo.addBatch();
            }
            if (errorCount % INSERT_BATCH_SIZE == 0) {
                insertError.executeBatch();
                insertInfo.executeBatch();
            }
            errorCount += 1;
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    public int getErrorCount () {
        return errorCount;
    }

    /**
     * This commits any remaining inserts, commits the transaction, and closes the connection permanently.
     * commitAndClose() should only be called when access to SQLErrorStorage is no longer needed.
     */
    public void commitAndClose() {
        try {
            // Execute any remaining batch inserts and commit the transaction.
            insertError.executeBatch();
            insertInfo.executeBatch();
            connection.commit();
            // Close the connection permanently (should be called only after errorStorage instance no longer needed).
            connection.close();
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void createErrorTables() {
        try {
            Statement statement = connection.createStatement();
            // If tables are dropped, order matters because of foreign keys.
            // TODO add foreign key constraint on info table?
            statement.execute(String.format("create table %serrors (error_id integer primary key, error_type varchar, " +
                    "entity_type varchar, line_number integer, entity_id varchar, entity_sequence integer, " +
                    "bad_value varchar)", tablePrefix));
            statement.execute(String.format("create table %serror_info (error_id integer, key varchar, value varchar)",
                    tablePrefix));
            connection.commit();
            // Keep connection open, closing would null the wrapped connection and return it to the pool.
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void createPreparedStatements () {
        try {
            insertError = connection.prepareStatement(
                    String.format("insert into %serrors values (?, ?, ?, ?, ?, ?, ?)", tablePrefix));
            insertInfo = connection.prepareStatement(
                    String.format("insert into %serror_info values (?, ?, ?)", tablePrefix));
        } catch (SQLException ex) {
            throw new StorageException(ex);
        }
    }

    private void reconnectErrorTables () {
        try {
            Statement statement = connection.createStatement();
            statement.execute(String.format("select max(error_id) from %serrors", tablePrefix));
            ResultSet resultSet = statement.getResultSet();
            resultSet.next();
            errorCount = resultSet.getInt(1);
            LOG.info("Reconnected to errors table, max error ID is {}.", errorCount);
            errorCount += 1; // Error count is zero based, add one to avoid duplicate error key
        } catch (SQLException ex) {
            throw new StorageException("Could not connect to errors table.", ex);
        }
    }

}
