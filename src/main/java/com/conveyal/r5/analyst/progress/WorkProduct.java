package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.controllers.UserActivityController;
import com.conveyal.analysis.models.Model;

/**
 * A unique identifier for the final product of a single task action. Currently this serves as both an
 * internal data structure and an API model class, which should be harmless as it's an immutable data class.
 * The id is unique within the type, so the regionId is redundant information, but facilitates prefectches on the UI.
 */
public class WorkProduct {

    public final WorkProductType type;
    public final String id;
    public final String regionId;

    public WorkProduct (WorkProductType type, String id, String regionId) {
        this.type = type;
        this.id = id;
        this.regionId = regionId;
    }

    // FIXME Not all Models have a regionId. Rather than pass that in as a String, refine the programming API.
    public static WorkProduct forModel (Model model) {
        WorkProduct product = new WorkProduct(WorkProductType.forModel(model), model._id, null);
        return product;
    }

}
