package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * A GTFS boolean field, coded as a single character string 0 or 1.
 */
public class BooleanField extends Field {

    public BooleanField (String name, Requirement requirement) {
        super(name, requirement);
    }

    private boolean validate (String string) {
        if ( ! ("0".equals(string) || "1".equals(string))) throw new StorageException("Field must be 0 or 1.");
        return "1".equals(string);
    }

    @Override
    public void setParameter (PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setBoolean(oneBasedIndex, validate(string));
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    /**
     * The 0 or 1 will be converted to the string "true" or "false" for SQL COPY.
     */
    @Override
    public String validateAndConvert (String string) {
        return Boolean.toString(validate(string));
    }

    @Override
    public SQLType getSqlType () {
        return JDBCType.BOOLEAN;
    }

}
