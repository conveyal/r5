package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * Created by abyrd on 2017-03-31
 */
public class ShortField extends Field {

    private int maxValue; // can be shared with all numeric field types?

    public ShortField (String name, Requirement requirement, int maxValue) {
        super(name, requirement);
        this.maxValue = maxValue;
    }

    private short validate (String string) {
        if (string == null || string.isEmpty()) return 0; // Default numeric fields to zero.
        short s = Short.parseShort(string);
        if (s < 0) throw new StorageException("negative field in " + name  );
        // TODO enforce
        // if (s > maxValue) throw new StorageException("excessively large short integer value in field " + name);
        return s;
    }

    @Override
    public void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setShort(oneBasedIndex, validate(string));
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    @Override
    public String validateAndConvert(String string) {
        validate(string);
        return string;
    }

    @Override
    public SQLType getSqlType () {
        return JDBCType.SMALLINT;
    }

}
