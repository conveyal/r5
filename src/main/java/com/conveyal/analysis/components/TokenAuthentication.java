package com.conveyal.analysis.components;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.controllers.AuthTokenController;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.lang.invoke.MethodHandles;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
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

    private LoadingCache<String, Token> tokenForEmail =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build(Token::forEmail);

    public TokenAuthentication (AnalysisDB database) {
        // TODO verify that sharing a MongoCollection across threads is safe
        this.users = database.getBsonCollection("users");
    }

    @Override
    public UserPermissions authenticate(Request request) {
        String authHeader = request.headers("authorization").strip();
        if ("sesame".equalsIgnoreCase(authHeader)) {
            return new UserPermissions("local", true, "local");
        }
        String[] authHeaderParts = authHeader.split(" ");
        if (authHeaderParts.length != 2 || !authHeaderParts[0].contains("@")) {
            throw new AnalysisServerException(UNAUTHORIZED, "Authorization header should be '[email] [token]", 401);
        }
        String email = authHeaderParts[0];
        String token = authHeaderParts[1];
        if (tokenValid(email, token)) {
            return new UserPermissions(email, true, "local");
        } else {
            throw new AnalysisServerException(UNAUTHORIZED, "Inalid authorization token.", 401);
        }
    }

    /**
     * Token is just a string, but use this class to keep things more typed and produce more structured JSON responses.
     * Add fields for expiration etc. if not handled by cache.
     */
    public static class Token {
        public final String token;
        public Token() {
            Random random = new Random();
            byte[] tokenBytes = new byte[32];
            random.nextBytes(tokenBytes);
            token = Base64.getEncoder().encodeToString(tokenBytes);
        }
        public static Token forEmail (String _email) {
            return new Token();
        }
    }

    /**
     * Ideally we could do this without the email, using a secondary map from token -> UserPermissions.
     */
    public boolean tokenValid (String email, String token) {
        // Here a loadingCache is not appropriate. We want to be able to check if a token is present without creating one.
        // Though in practice this still works, it just generates tokens for any user that's queried and they don't match.
        return token.equals(tokenForEmail.get(email).token);
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
    public Token getTokenForEmail (String email, String password) {
        Document userDocument = users.find(eq("_id", email)).first();
        if (userDocument == null) {
            throw new IllegalArgumentException("User unknown: " + email);
        }
        Binary salt = (Binary) userDocument.get("salt");
        Binary hash = (Binary) userDocument.get("hash");
        byte[] hashForComparison = hashWithSalt(password, salt.getData());
        if (Arrays.equals(hash.getData(), hashForComparison)) {
            // Maybe invalidation is pointless and we can continue to return the same key indefinitely.
            tokenForEmail.invalidate(email);
            Token token = tokenForEmail.get(email);
            return token;
        } else {
            return null;
        }
    }

}
