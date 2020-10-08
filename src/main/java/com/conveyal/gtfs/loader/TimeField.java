package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * A field in the format HH:MM:SS, which will be stored as a number of seconds after midnight.
 */
public class TimeField extends Field {

    public TimeField(String name, Requirement requirement) {
        super(name, requirement);
    }

    @Override
    public void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setInt(oneBasedIndex, getSeconds(string));
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    // Actually this is converting the string. Can we use some JDBC existing functions for this?
    @Override
    public String validateAndConvert(String hhmmss) {
        return Integer.toString(getSeconds(hhmmss));
    }

    private static int getSeconds (String hhmmss) {
        if (hhmmss.length() != 8) {
            throw new StorageException("Time field should be 8 characters long.");
        }
        String[] fields = hhmmss.split(":");
        int h = Integer.parseInt(fields[0]);
        int m = Integer.parseInt(fields[1]);
        int s = Integer.parseInt(fields[2]);
        return ((h * 60) + m) * 60 + s;
    }

    @Override
    public SQLType getSqlType () {
        return JDBCType.INTEGER;
    }

}
