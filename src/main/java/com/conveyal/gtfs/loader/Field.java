package com.conveyal.gtfs.loader;

import java.sql.PreparedStatement;
import java.sql.SQLType;

/**
 * Field subclasses process an incoming String that represents a single GTFS CSV field value.
 * The value is validated and converted to its final format.
 * We need to propagate any validation errors up to the caller (where file, line, and column number context are known),
 * Unfortunately Java does not allow multiple return values. There are multiple options.
 * We could emulate multiple return by wrapping the resulting String in another object that combines it with an error type.
 * We could pass a list into every function and the functions could add errors to that list.
 * We could make the Field instances have state, which will also make them single-use and single-thread. They could
 * then accumulate errors as they do their work.
 * We could return an error or list of errors from functions that store the validated value into an array passed in as a parameter.
 * In all cases, to avoid enormous amounts of useless object creation we could re-use error lists and just clear them
 * before each validation operation.
 * However, within the Field implementations, we may need to call private/internal functions that also return multiple
 * values (an error and a modified value).
 */
public abstract class Field {

    final String name;
    final Requirement requirement;

    public Field(String name, Requirement requirement) {
        this.name = name;
        this.requirement = requirement;
    }

    /**
     * Check the supplied string to see if it can be parsed as the proper data type.
     * Perform any conversion (I think this is only done for times, to integer numbers of seconds).
     * @param original a non-null String
     * @return a string that is parseable as this field's type, or null if it is not parseable
     */
    public abstract String validateAndConvert(String original);

    public abstract void setParameter(PreparedStatement preparedStatement, int oneBasedIndex, String string);

    public abstract SQLType getSqlType ();

    // Overridden to create exception for "double precision", since its enum value is just called DOUBLE.
    public String getSqlTypeName () {
        return getSqlType().getName().toLowerCase();
    }

    public String getSqlDeclaration() {
        return String.join(" ", name, getSqlTypeName());
    }

    // TODO test for input with tabs, newlines, carriage returns, and slashes in it.
    protected static String cleanString (String string) {
        // Backslashes, newlines, and tabs have special meaning to Postgres.
        // String.contains is significantly faster than using a regex or replace, and has barely any speed impact.
        if (string.contains("\\")) {
            string = string.replace("\\", "\\\\");
        }
        if (string.contains("\t") || string.contains("\n") || string.contains("\r")) {
            // TODO record error and recover, and use a single regex
            string = string.replace("\t", " ");
            string = string.replace("\n", " ");
            string = string.replace("\r", " ");
        }
        return string;
    }

    /**
     * Generally any required field should be present on every row.
     * TODO override this method for exceptions, e.g. arrival and departure can be missing though the field must be present
     */
    public boolean missingRequired (String string) {
        return  (string == null || string.isEmpty()) && this.isRequired();
    }

    public boolean isRequired () {
        return this.requirement == Requirement.REQUIRED;
    }

}
