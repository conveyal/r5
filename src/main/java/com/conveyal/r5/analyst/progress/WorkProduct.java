package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.models.BaseModel;

/**
 * A unique identifier for the final product of a single task action. Currently this serves as both an internal data
 * structure and an API model class, which should be harmless as it's an immutable data class. The id is unique within
 * the type, so the regionId is redundant information, but facilitates prefectches on the UI. If isGroup is true, the
 * id is not that of an individual record, but the dataGroupId of several records created in a single operation.
 */
public class WorkProduct {

    public final WorkProductType type;
    public final String id;
    public final String regionId;
    public final boolean isGroup;

    public WorkProduct (WorkProductType type, String id, String regionId) {
        this(type, id, regionId, false);
    }

    public WorkProduct (WorkProductType type, String id, String regionId, boolean isGroup) {
        this.type = type;
        this.id = id;
        this.regionId = regionId;
        this.isGroup = isGroup;
    }

    // FIXME Not all Models have a regionId. Rather than pass that in as a String, refine the programming API.
    public static WorkProduct forModel (BaseModel model) {
        return new WorkProduct(WorkProductType.forModel(model), model._id.toString(), null);
    }

    public static WorkProduct forDataGroup (WorkProductType type, String dataGroupId, String regionId) {
        return new WorkProduct(type, dataGroupId, regionId, true);
    }
}
