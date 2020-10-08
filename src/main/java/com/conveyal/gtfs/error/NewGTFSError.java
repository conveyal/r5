package com.conveyal.gtfs.error;

import com.conveyal.gtfs.loader.Table;
import com.conveyal.gtfs.model.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * TODO make hierarchy of feedError, tableError, lineError each adding fields and calling constructors.
 *
 * There's a good reason not to use a class hierarchy for errors: we need to instantiate errors from a database.
 * We don't want to use reflection to call different constructors based on the error-type field in the database.
 * We instead use an enum with final fields for severity and affected entity type.
 */
public class NewGTFSError {

    /** The class of the table in which the error was encountered. */
    public final Class<? extends Entity> entityType;

    /** The kind of error encountered. */
    public final NewGTFSErrorType errorType;

    /** Key-value pairs providing additional information about this error. */
    public Map<String, String> errorInfo = new HashMap<>();

    /**
     * The line of the GTFS table (one-based, including the header) on which the error was detected.
     * It is unlikely we'll see files with over 2 billion lines (3 orders of magnitude greater than NL) so 31 bits is enough.
     * Use Integer object rather than primitive to allow NULL because some errors affect an entire table or feed.
     */
    public Integer lineNumber = null;

    // We could hold on to the Entity itself here, because these objects are very short lived.
    // However, extracting fields out of it allows this to serve as a general model for errors even when
    // the entities are gone. We could then re-load them from the database if needed.
    // If it turns out we never need to load them back from the database into Java, then this class is unnecessary
    // and all logic can be pushed down into the errorStorage methods.
    // One advantage of having this class represent errors is the ability to accumulate additional key-value
    // info into the error before storing it.

    /**
     * The id part of the unique key of the line containing the error (convenient but redundant info).
     */
    public String entityId = null;

    /**
     * The sequence number part of the unique key of the line containing the error (when needed for uniqueness).
     */
    public Integer entitySequenceNumber = null;

    public String badValue = null;

    /**
     * Add a single key-value pair of supplemental info to this error.
     * @return the modified object (builder pattern that allows adding info to newly created error object without assigning to a variable).
     */
    public NewGTFSError addInfo (String key, String value) {
        errorInfo.put(key, value);
        return this;
    }


    /**
     * Create an error object affecting a whole table or a whole feed.
     * @param entityType should be supplied for a table, may be null if the error affects a whole feed.
     * @param errorType must always be supplied.
     */
    private NewGTFSError (Class<? extends Entity> entityType, NewGTFSErrorType errorType) {
        this.entityType = entityType;
        this.errorType = errorType;
    }

    // Factory Builder for cases where an entity has not yet been constructed, but we know the line number.
    public static NewGTFSError forLine (Table table, int lineNumber, NewGTFSErrorType errorType, String badValue) {
        NewGTFSError error = new NewGTFSError(table.getEntityClass(), errorType);
        error.lineNumber = lineNumber;
        error.badValue = badValue;
        return error;
    }

    // Factory Builder for cases where the entity has already been decoded and an error is discovered during validation
    public static NewGTFSError forEntity(Entity entity, NewGTFSErrorType errorType) {
        NewGTFSError error = new NewGTFSError(entity.getClass(), errorType);
        error.lineNumber = entity.sourceFileLine;
        error.entityId = entity.getId();
        error.entitySequenceNumber = entity.getSequenceNumber();
        return error;
    }

    // Factory Builder
    public static NewGTFSError forTable (Table table, NewGTFSErrorType errorType) {
        return new NewGTFSError(table.getEntityClass(), errorType);
    }

    // Factory Builder for feed-wide error
    public static NewGTFSError forFeed (NewGTFSErrorType errorType, String badValue) {
        return new NewGTFSError(null, errorType).setBadValue(badValue);
    }

    // Builder to add bad value info
    public NewGTFSError setBadValue (String badValue) {
        this.badValue = badValue;
        return this;
    }

}
