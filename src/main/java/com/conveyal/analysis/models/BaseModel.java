package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * The base type for objects stored in our newer AnalysisDB using the Mongo Java driver's POJO functionality.
 */
public abstract class BaseModel {
    public String _id;

    // For version management.
    public String nonce;

    public String createdBy = null;
    public String updatedBy = null;

    public Date createdAt = null;
    public Date updatedAt = null;

    // Who owns this?
    public String accessGroup = null;

    // Everything has a name
    public String name = null;

    public BaseModel(UserPermissions user, String name) {
        this(user);
        this.name = name;
    }

    public BaseModel(UserPermissions user) {
        this._id = new ObjectId().toString();
        this.nonce = new ObjectId().toString();
        this.createdAt = new Date();
        this.updatedAt = new Date();
        this.createdBy = user.email;
        this.updatedBy = user.email;
        this.accessGroup = user.accessGroup;
    }

    /**
     * Zero argument constructor required for MongoDB driver automatic POJO deserialization.
     */
    public BaseModel() {
    }
}
