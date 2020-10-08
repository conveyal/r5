package com.conveyal.analysis.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.bson.types.ObjectId;

import javax.persistence.Id;
import java.util.Date;

import static com.conveyal.analysis.util.JsonUtil.objectMapper;

/**
 * Shared superclass for data model classes that are serialized to communicate between the UI and the backend,
 * and generally stored in the application MongoDB.
 * Other objects that are serialized and sent to the workers are not subclasses of this.
 */
public abstract class Model implements Cloneable {

    @Id
    public String _id;

    public String name;

    public String nonce;

    public Date createdAt;
    public Date updatedAt;

    public void updateLock() {
        this.nonce = new ObjectId().toString();
        this.updatedAt = new Date();
    }

    public String accessGroup;
    public String createdBy;
    public String updatedBy;

    /**
     * Since this is the common superclass for objects that are sent over an HTTP API as JSON and stored in MongoDB
     * (where objects are also commonly represented as JSON) our default human-readable representation is as JSON.
     * However, we avoid relying on this behavior to produce machine-readable output, preferring to explicitly
     * specify conversion to JSON rather than relying on implicit toString behavior.
     */
    @Override
    public String toString () {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return super.toString();
        }
    }
}
