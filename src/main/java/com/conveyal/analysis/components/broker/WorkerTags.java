package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.RegionalAnalysis;

/**
 * An immutable group of tags to be added to the worker instance to assist in usage analysis and cost breakdowns.
 * These Strings are purely for categorization of workers and should not be used for other purposes,
 * only passed through to the AWS SDK.
 */
public class WorkerTags {

    /** The unique name of the permissions group under which the user is working. */
    public final String group;

    /** A unique ID for the user (the user's email address). */
    public final String user;

    /** The UUID for the region. */
    public final String regionId;

    public WorkerTags (UserPermissions userPermissions, String regionId) {
        this(userPermissions.accessGroup, userPermissions.email, regionId);
    }

    public WorkerTags (String group, String user, String regionId) {
        this.group = group;
        this.user = user;
        this.regionId = regionId;
    }

    public static WorkerTags fromRegionalAnalysis (RegionalAnalysis regionalAnalysis) {
        return new WorkerTags(
                regionalAnalysis.accessGroup,
                regionalAnalysis.createdBy,
                regionalAnalysis.regionId
        );
    }

}
