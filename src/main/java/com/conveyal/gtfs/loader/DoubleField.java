package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;

import static com.conveyal.gtfs.error.NewGTFSErrorType.NUMBER_TOO_LARGE;
import static com.conveyal.gtfs.error.NewGTFSErrorType.NUMBER_TOO_SMALL;

/**
 * Created by abyrd on 2017-03-31
 */
public class DoubleField extends Field {

    private double minValue;

    private double maxValue;

    public DoubleField (String name, Requirement requirement, double minValue, double maxValue) {
        super(name, requirement);
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    private double validate(String string) {
        double d = Double.parseDouble(string);
        if (d < minValue) throw new StorageException(NUMBER_TOO_SMALL, Double.toString(d));
        if (d > maxValue) throw new StorageException(NUMBER_TOO_LARGE, Double.toString(d));
        return d;
    }

    @Override
    public void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setDouble(oneBasedIndex, validate(string));
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
        return JDBCType.DOUBLE;
    }

    @Override
    public String getSqlTypeName () {
        return "double precision";
    }

}
