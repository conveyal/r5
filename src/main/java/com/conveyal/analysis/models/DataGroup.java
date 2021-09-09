package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import org.bson.types.ObjectId;

/**
 * When deriving data (layers, networks, etc.) from a DataSource, we sometimes produce many outputs at once from
 * the same source and configuration options. We group all those derived products together using a DataGroup.
 * The grouping is achieved simply by multiple other entities of the same type referencing the same dataGroupId.
 * The DataGroups don't have many other characteristics of their own. They are materialized and stored in Mongo just
 * to provide a user-editable name/description for the group.
 */
public class DataGroup extends BaseModel {

    /** The data source this group of products was derived from. */
    public String dataSourceId;

    public DataGroup (UserPermissions user, String dataSourceId, String description) {
        super(user, description);
        this.dataSourceId = dataSourceId;
    }

}
