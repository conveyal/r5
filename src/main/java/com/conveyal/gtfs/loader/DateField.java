package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.error.NewGTFSErrorType;
import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A GTFS date in the numeric format YYYYMMDD
 */
public class DateField extends Field {

    private static final DateTimeFormatter gtfsDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd");

    public DateField (String name, Requirement requirement) {
        super(name, requirement);
    }

    private int validate (String string) {
        // Parse the date out of the supplied string.
        LocalDate date = null;
        try {
            date = LocalDate.parse(string, gtfsDateFormat);
        } catch (DateTimeParseException ex) {
            throw new StorageException(NewGTFSErrorType.DATE_FORMAT, string);
        }
        // Range check on year. Parsing operation above should already have checked month and day ranges.
        int year = date.getYear();
        if (year < 2000 || year > 2100) {
            throw new StorageException(NewGTFSErrorType.DATE_RANGE, string);
        }
        // Finally store as int (is that really a good idea?)
        return Integer.parseInt(string);
    }

    @Override
    public void setParameter (PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setInt(oneBasedIndex, validate(string));
        } catch (Exception ex) {
            throw new StorageException(ex);
        }
    }

    @Override
    public String validateAndConvert (String string) {
        return Integer.toString(validate(string));
    }

    @Override
    public SQLType getSqlType () {
        return JDBCType.INTEGER;
    }

}
