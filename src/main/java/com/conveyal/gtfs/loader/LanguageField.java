package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.storage.StorageException;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLType;
import java.util.Locale;

import static com.conveyal.gtfs.error.NewGTFSErrorType.LANGUAGE_FORMAT;

/**
 * Checks a BCP47 language tag.
 */
public class LanguageField extends Field {

    public LanguageField (String name, Requirement requirement) {
        super(name, requirement);
    }

    private String validate (String string) {
        Locale locale = Locale.forLanguageTag(string);
        String generatedTag = locale.toLanguageTag();
        // This works except for hierarchical sublanguages like zh-cmn and zh-yue which get flattened to the sublanguage.
        if (!generatedTag.equalsIgnoreCase(string)) {
            throw new StorageException(LANGUAGE_FORMAT, string);
        }
        return string;
    }

    /** Check that a string can be properly parsed and is in range. */
    public String validateAndConvert (String string) {
        return cleanString(validate(string));
    }

    public void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string) {
        try {
            preparedStatement.setString(oneBasedIndex, validateAndConvert(string));
        } catch (Exception ex) {
            throw new StorageException(LANGUAGE_FORMAT, string);
        }
    }

    @Override
    public SQLType getSqlType() {
        return JDBCType.VARCHAR;
    }

}
