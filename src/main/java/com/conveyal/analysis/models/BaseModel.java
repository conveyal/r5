package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

/** The base type for objects stored in our newer AnalysisDB using the Mongo Java driver's POJO functionality. */
public class BaseModel {
    // Can retrieve `createdAt` from here
    public ObjectId _id;

    // For version management. ObjectId's contain a timestamp, so can retrieve `updatedAt` from here.
    public ObjectId nonce;

    public String createdBy = null;
    public String updatedBy = null;

    // Who owns this?
    public String accessGroup = null;

    // Everything has a name
    public String name = null;

    // package private to encourage use of static factory methods
    BaseModel (UserPermissions user, String name) {
        this._id = new ObjectId();
        this.nonce = new ObjectId();
        this.createdBy = user.email;
        this.updatedBy = user.email;
        this.accessGroup = user.accessGroup;
        this.name = name;
    }

    /** Zero argument constructor required for MongoDB driver automatic POJO deserialization. */
    public BaseModel () { }

}
