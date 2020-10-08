package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * Represents a six-digit hex color code. Should be parsed as hex text and less than OxFFFFFF.
 *
 * Created by abyrd on 2017-03-30
 */
public class ColorField extends Field {

    public ColorField(String name, Requirement requirement) {
        super(name, requirement);
    }

    /** Check that a string can be properly parsed and is in range. */
    public String validateAndConvert (String string) {
        try {
            if (string.length() != 6) {
                throw new StorageException("Expected color string to have six characters.");
            }
            int integer = Integer.parseInt(string, 16);
            return string; // Could also store the integer.
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

    /**
     * From the Postgres manual, on character types:
     * There is no performance difference among these three types, apart from increased storage space when using
     * the blank-padded type, and a few extra CPU cycles to check the length when storing into a length-constrained
     * column. While character(n) has performance advantages in some other database systems, there is no such advantage
     * in PostgreSQL; in fact character(n) is usually the slowest of the three because of its additional storage costs.
     *
     * Arguably colors are integers expressed in hex though.
     */
    @Override
    public SQLType getSqlType() {
        return JDBCType.VARCHAR;
    }

}
