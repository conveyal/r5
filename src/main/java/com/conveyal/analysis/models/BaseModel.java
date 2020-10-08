package com.conveyal.analysis.models;

import org.bson.types.ObjectId;

public class BaseModel {
    // Can retrieve `createdAt` from here
    public ObjectId _id;

    // For version management. ObjectId's contain a timestamp, so can retrieve `updatedAt` from here.
    public ObjectId nonce = new ObjectId();

    public String createdBy = null;
    public String updatedBy = null;

    // Who owns this?
    public String accessGroup = null;

    // Everything has a name
    public String name = null;
}
