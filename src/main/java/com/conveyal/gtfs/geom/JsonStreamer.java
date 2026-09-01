package com.conveyal.gtfs.geom;

import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;

/// Minimal helpers wrapping the Jackson streaming parser, for use in hand-written streaming readers.
///
/// A uniform cursor positioning convention is applied. Loops position the cursor ON the first
/// token of each value before acting on it. Therefore, the value-handling methods they call only
/// ever examine the current token. The fieldName method establishes this convention by advancing
/// past each field name to its value. SkipValue is the uniform way to ignore any field. This
/// convention avoids needing a mix of methods that act on the current vs. next token.
public abstract class JsonStreamer {

    protected final JsonParser jp;

    /// The parser created here auto-detects the input's encoding, including handling any UTF
    /// byte order mark, which GTFS explicitly allows. JSON allows only UTF-8/16/32, and Jackson's
    /// ByteSourceJsonBootstrapper.constructParser calls detectEncoding, which handles BOMs.
    public JsonStreamer (InputStream inputStream) {
        try {
            jp = JsonUtilities.objectMapper.getFactory().createParser(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JSON parser.", e);
        }
    }

    /// Throw an exception if the current token does not match the expected type.
    protected void expect (JsonToken expected) {
        if (jp.currentToken() != expected) {
            throw new IllegalArgumentException(
                "Expected " + expected + " in JSON input but found " + jp.currentToken() + ".");
        }
    }

    /// Advances to the next token, throwing an exception if it is not of the expected type.
    protected void advance (JsonToken expected) throws IOException {
        jp.nextToken();
        expect(expected);
    }

    /// At a field name token, returns the name and advances the cursor onto the field's value.
    protected String fieldName () throws IOException {
        expect(JsonToken.FIELD_NAME);
        String name = jp.currentName();
        jp.nextToken();
        return name;
    }

    /// Returns the current token as a String value.
    protected String stringValue () throws IOException {
        expect(JsonToken.VALUE_STRING);
        return jp.getText();
    }

    /// Throws an exception unless the current token is exactly the given String value.
    protected void expectString (String value) throws IOException {
        if (!value.equals(stringValue())) {
            throw new IllegalArgumentException("Expected exact string value in JSON: " + value);
        }
    }

    /// Consumes the current value however large it is. For an array or object,
    /// this means everything through its matching closing token.
    protected void skipValue () throws IOException {
        jp.skipChildren();
    }

}
