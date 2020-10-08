package com.conveyal.analysis.components.eventbus;

/**
 * Signals that a user has created, read, updated, or deleted a domain model object from the database.
 */
public class CrudEvent extends Event {

    enum Action {
        CREATE, READ, UPDATE, DELETE
    }

    public final Action action;

    public final String entityType;

    public final String entityId;

    public CrudEvent (Action action, String entityType, String entityId) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
    }

}
