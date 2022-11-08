package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * The base type for objects stored in our newer AnalysisDB using the Mongo Java driver's POJO functionality.
 */
public class BaseModel {
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

    // package private to encourage use of static factory methods
    BaseModel(UserPermissions user, String name) {
        this._id = new ObjectId().toString();
        this.nonce = new ObjectId().toString();
        this.createdBy = user.email;
        this.updatedBy = user.email;
        this.createdAt = new Date();
        this.updatedAt = new Date();
        this.accessGroup = user.accessGroup;
        this.name = name;
    }

    /** Zero argument constructor required for MongoDB driver automatic POJO deserialization. */
    public BaseModel () { }
}
