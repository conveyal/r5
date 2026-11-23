package com.conveyal.gtfs.flex;

import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;

public abstract class JsonStreamer {

    final JsonParser jp;

    public JsonStreamer (InputStream inputStream) {
        try {
            jp = JsonUtilities.objectMapper.getFactory().createParser(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JSON parser.", e);
        }
    }

    /// Throw an exception if the supplied token does not match the expected type.
    static void expect (JsonToken actual, JsonToken expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Expected token in JSON input to be" + expected);
        }
    }

    /// Throw an exception if the supplied token is not one of the N types provided as the variadic arguments.
    static void expectAny (JsonToken actual, JsonToken... expected) {
        for (JsonToken oneExpected : expected) if (actual == oneExpected) return;
        throw new IllegalArgumentException("Expected token in JSON to be one of" + expected);
    }

    /// Enforces an expectation that the supplied token is either a float or an int. This deals
    /// with the fact that a JSON value that can be parsed into a floating point type may be
    /// detected as the separate integer type if it is expressed with no fractional part.
    static void expectNumber (JsonToken token) throws IOException {
        if (token != JsonToken.VALUE_NUMBER_FLOAT && token != JsonToken.VALUE_NUMBER_INT) {
            throw new IllegalArgumentException("Expected JSON integer or floating point number.");
        }
    }

    /// Enforces an expectation that the current (not next) token is of a specific kind.
    void expectCurrent (JsonToken token) throws IOException {
        expect(jp.currentToken(), token);
    }

    /// Enforces an expectation that the next (not current) token is of a specific kind.
    /// As a side effect, advances the parser to that next token.
    void expectNext (JsonToken token) throws IOException {
        expect(jp.nextToken(), token);
    }

    /// Enforces an expectation that the current (not next) token is an object field with any name.
    /// @return the name of the field
    String expectCurrentFieldName () throws IOException {
        expectCurrent(JsonToken.FIELD_NAME);
        return jp.currentName();
    }

    /// Enforces an expectation that the next (not current) token is a String and returns it.
    /// As a side effect, advances the parser to that next token.
    String expectNextString () throws IOException {
        expectNext(JsonToken.VALUE_STRING);
        return jp.getText();
    }

    /// Enforces an expectation that the current (not next) token is a number which may or may not
    /// be expressed with a fractional part but is parseable as a double, returning it as a double.
    double expectCurrentDouble () throws IOException {
        expectNumber(jp.currentToken());
        return jp.getDoubleValue();
    }

    /// Enforces an expectation that the next (not current) token is a number which may or may not
    /// be expressed with a fractional part but is parseable as a double, returning it as a double.
    /// As a side effect, advances the parser to that next token.
    double expectNextDouble () throws IOException {
        expectNumber(jp.nextToken());
        return jp.getDoubleValue();
    }

    /// Enforces an expectation that the next (not current) token is a specific String value.
    /// As a side effect, advances the parser to that next token.
    void expectNextString (String value) throws IOException {
        if (!value.equals(expectNextString())) {
            throw new IllegalArgumentException("Expected exact string value in JSON:" + value);
        }
    }

}
