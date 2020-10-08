package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Represents an error encountered while loading or validating a GFTS feed.
 *
 * TODO de-implement Comparable and Serializable
 * TODO parameterize with the type of affected entity <T extends Entity>, with pseudo-entity for lines?
 */
public abstract class GTFSError implements Comparable<GTFSError>, Serializable {

    public final String file; // TODO GTFSTable enum? Or simply use class objects.
    public final long   line;
    public final String field;
    public final String affectedEntityId;
    public final String errorType;

    public GTFSError(String file, long line, String field) {
        this(file, line, field, null);
    }

    public GTFSError(String file, long line, String field, String affectedEntityId) {
        this.file  = file;
        this.line  = line;
        this.field = field;
        this.affectedEntityId = affectedEntityId;
        this.errorType = this.getClass().getSimpleName();
    }

    /**
     * New constructor for SQL storage.
     */
    public GTFSError (String entityId) {
        this.file = null;
        this.line = -1;
        this.field = null;
        this.errorType = null;
        this.affectedEntityId = entityId;
    }

    /**
     * @return the name of this class without a package, to be used as a unique identifier for the kind of error.
     * By putting all these classes in the same package we guarantee they have unique names.
     */
    public final String getErrorCode () {
        return this.getClass().getSimpleName();
    }

    public Priority getPriority () {
        return Priority.UNKNOWN;
    }

    /**
     * @return a Class object for the class of GTFS entity in which the error was found,
     *         which also implies a table in the GTFS feed.
     */
    public Class<? extends Entity> getAffectedEntityType () {
        return null;
    }

    public String getMessage() {
        return "no message";
    }

    public String getMessageWithContext() {
        StringBuilder sb = new StringBuilder();
        sb.append(file);
        sb.append(' ');
        if (line >= 0) {
            sb.append("line ");
            sb.append(line);
        } else {
            sb.append("(no line)");
        }
        if (field != null) {
            sb.append(", field '");
            sb.append(field);
            sb.append('\'');
        }
        sb.append(": ");
        sb.append(getMessage());
        return sb.toString();
    }

    /** must be comparable to put into mapdb */
    public int compareTo (GTFSError o) {
        if (this.file == null && o.file != null) return -1;
        else if (this.file != null && o.file == null) return 1;

        int file = this.file == null && o.file == null ? 0 : String.CASE_INSENSITIVE_ORDER.compare(this.file, o.file);
        if (file != 0) return file;
        int errorType = String.CASE_INSENSITIVE_ORDER.compare(this.errorType, o.errorType);
        if (errorType != 0) return errorType;
        int affectedEntityId = this.affectedEntityId == null && o.affectedEntityId == null ? 0 : String.CASE_INSENSITIVE_ORDER.compare(this.affectedEntityId, o.affectedEntityId);
        if (affectedEntityId != 0) return affectedEntityId;
        else return Long.compare(this.line, o.line);
    }

    @Override
    public String toString() {
        return "GTFSError: " + getMessageWithContext();
    }



}
