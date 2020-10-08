package com.conveyal.analysis.components.eventbus;

import com.conveyal.r5.analyst.WorkerCategory;

/**
 * Created by abyrd on 2020-06-12
 */
public class WorkerEvent extends Event {

    public enum Role {
        SINGLE_POINT, REGIONAL
    }

    public enum Action {
        REQUESTED, STARTED, SHUT_DOWN
    }

    public final Role role;
    public final WorkerCategory category;
    public final Action action;
    public final int quantity;

    public WorkerEvent (Role role, WorkerCategory category, Action action, int quantity) {
        this.role = role;
        this.category = category;
        this.action = action;
        this.quantity = quantity;
    }
}
