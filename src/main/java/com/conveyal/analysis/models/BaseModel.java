package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

public class BaseModel {
    // Can retrieve `createdAt` from here
    public ObjectId _id;

    public String createdBy = null;
    public String updatedBy = null;

    // Who owns this?
    public String accessGroup = null;

    // Everything has a name
    public String name = null;

    // package private to encourage use of static factory methods
    BaseModel (UserPermissions user, String name){
        this._id = new ObjectId();
        this.createdBy = user.email;
        this.updatedBy = user.email;
        this.accessGroup = user.accessGroup;
        this.name = name;
    }


    BaseModel () {
        // No-arg
    }

}
