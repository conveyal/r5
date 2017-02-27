package com.conveyal.r5.analyst.error;

import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This is an API model object for reporting a single error or warning that occurred on a worker back to the client via the broker.
 * The most common errors a user will see are problems applying scenario modifications, so this provides some fields
 * to clarify what modification caused the error.
 * But it can also wrap any old Exception to report more unexpected kinds of errors.
 */
public class TaskError {

    public final String modificationId;
    public final String title;
    public final List<String> messages = new ArrayList<>();

    /** This constructor is used when an unexpected, unhandled error is encountered. */
    public TaskError(Exception ex) {
        this.modificationId = null;
        this.title = "Unhandled error: " + ex.toString();
        this.messages.add(ExceptionUtils.asString(ex));
    }

    /**
     * This constructor is used for errors that occur while applying a scenario to a network.
     * messages will generally be either the errors or warnings associated with the modification, which is why there is
     * a separate argument; otherwise we wouldn't know whether errors or warnings were desired.
     */
    public TaskError(Modification modification, Collection<String> messages) {
        this.modificationId = modification.comment;
        this.title = "while applying the modification entitled '" + modification.comment + "'.";
        this.messages.addAll(messages);
    }

    public TaskError(String modificationId, String title, String detail) {
        this.modificationId = modificationId;
        this.title = title;
        this.messages.add(detail);
    }

}
