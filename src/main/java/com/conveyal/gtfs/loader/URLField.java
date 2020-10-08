package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * Created by abyrd on 2017-03-31
 */
public class URLField extends Field {

    public URLField(String name, Requirement requirement) {
        super(name, requirement);
    }

    /** Check that a string can be properly parsed and is in range. */
    public String validateAndConvert (String string) {
        try {
            string = cleanString(string);
            // new URL(cleanString); TODO call this to validate, but we can't default to zero
            return string;
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    public void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setString(oneBasedIndex, validateAndConvert(string));
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    @Override
    public SQLType getSqlType() {
        return JDBCType.VARCHAR;
    }

}
