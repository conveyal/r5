package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

public class BaseModel {
    // Can retrieve `createdAt` from here
    public final ObjectId _id = new ObjectId();

    // For version management. ObjectId's contain a timestamp, so can retrieve `updatedAt` from here.
    public ObjectId nonce = new ObjectId();

    public String createdBy = null;
    public String updatedBy = null;

    // Who owns this?
    public String accessGroup = null;

    // Everything has a name
    public String name = null;

    // package private to encourage use of static factory methods
    BaseModel (UserPermissions user, String name) {
        this.createdBy = user.email;
        this.updatedBy = user.email;
        this.accessGroup = user.accessGroup;
        this.name = name;
    }

    /**
     * No-arg constructor required for Mongo POJO serialization
     */
    public BaseModel () {
        
    }
}
