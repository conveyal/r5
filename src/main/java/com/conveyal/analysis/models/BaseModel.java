package com.conveyal.analysis.models;

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
}
