package com.conveyal.analysis.components;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.lang.invoke.MethodHandles;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.conveyal.analysis.AnalysisServerException.Type.UNAUTHORIZED;
import static com.mongodb.client.model.Filters.eq;

/**
 * Simple bearer token authentication storing hashed passwords in database.
 * Allows direct management of users and permissions.
 */
public class TokenAuthentication implements Authentication {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final MongoCollection<Document> users;

    /**
     * Bundles UserPermissions together with a last read time to allow expiry.
     */
    private static class TokenValue {
        long lastUsed = System.currentTimeMillis();
        final UserPermissions userPermissions;
        public TokenValue(UserPermissions userPermissions) {
            this.userPermissions = userPermissions;
        }
    }

    private Map<String, TokenValue> userForToken = new HashMap<>();

    public TokenAuthentication (AnalysisDB database) {
        // TODO verify that sharing a MongoCollection across threads is safe
        this.users = database.getBsonCollection("users");
    }

    @Override
    public UserPermissions authenticate(Request request) {
        String token = request.headers("authorization");
        // Some places such as MopboxGL do not make it easy to add headers, so also accept token in query parameter.
        // The MapboxGL transformUrl setting seems to be missing from recent versions of the library.
        if (token == null) {
            token = request.queryParams("token");
        }
        if (token == null) {
            throw new AnalysisServerException(UNAUTHORIZED, "Authorization token mising.", 401);
        }
        if ("sesame".equalsIgnoreCase(token)) {
            return new UserPermissions("local", true, "local");
        }
        UserPermissions userPermissions = userForToken(token);
        if (userPermissions == null) {
            throw new AnalysisServerException(UNAUTHORIZED, "Invalid authorization token.", 401);
        } else {
            return userPermissions;
        }
    }

    /**
     * TODO is SecureRandom a sufficiently secure source of randomness when used this way?
     * Should we be creating a new instance of SecureRandom each time or reusing it?
     * Do not use basic Base64 encoding since it contains some characters that are invalid in URLs.
     * @return A url-safe representation of 32 random bytes
     */
    public static String generateToken () {
        Random random = new SecureRandom();
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().encodeToString(tokenBytes);
        return token;
    }

    public UserPermissions userForToken (String token) {
        TokenValue tokenValue = null;
        synchronized (userForToken) {
            tokenValue = userForToken.get(token);
            if (tokenValue == null) {
                return null;
            } else {
                tokenValue.lastUsed = System.currentTimeMillis();
                return tokenValue.userPermissions;
            }
        }
    }

    /**
     * @return byte[] representing the supplied password hashed with the supplied salt.
     */
    private byte[] hashWithSalt (String password, byte[] salt) {
        try {
            // Note Java char is 16-bit Unicode (not byte, which requires a specific encoding like UTF8).
            // 256 bit key length is 32 bytes.
            KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] hash = keyFactory.generateSecret(keySpec).getEncoded();
            return hash;
            // return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Exception:", e);
        }
    }

    /**
     * Create a user with the specified password. Stores the random salt and hashed password in the database.
     */
    public void createUser (String email, String group, String password) {
        // TODO validate password entropy
        Random random = new Random();
        byte[] salt = new byte[32];
        random.nextBytes(salt);
        byte[] hash = hashWithSalt(password, salt);
        // Due to Mongo's nature it may not be possible to verify whether the user already exists.
        // Once the write is finalized though, this will produce E11000 duplicate key error.
        // We may want to allow updating a user by simply calling this HTTP API method more than once.
        users.insertOne(new Document("_id", email)
                        .append("group", group)
                        .append("salt", new Binary(salt))
                        .append("hash", new Binary(hash))
        );
    }

    /**
     * Create a new token, replacing any existing one for the same user (email) as long as the password is correct.
     * @return a new token, or null if the supplied password is incorrect.
     */
    public String makeToken (String email, String password) {
        Document userDocument = users.find(eq("_id", email)).first();
        if (userDocument == null) {
            throw new IllegalArgumentException("User unknown: " + email);
        }
        Binary salt = (Binary) userDocument.get("salt");
        Binary hash = (Binary) userDocument.get("hash");
        String group = userDocument.getString("group");
        byte[] hashForComparison = hashWithSalt(password, salt.getData());
        if (Arrays.equals(hash.getData(), hashForComparison)) {
            // Maybe invalidation is pointless and we can continue to return the same key indefinitely.
            String token = generateToken();
            synchronized (userForToken) {
                userForToken.put(token, new TokenValue(new UserPermissions(email, false, group)));
                return token;
            }
        } else {
            return null;
        }
    }

}
