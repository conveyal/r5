package com.conveyal.r5.analyst.error;

import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * This is an API model object for reporting a single error or warning that occurred on a worker back to the UI via
 * the backend. The most common errors a user will see are problems applying scenario modifications, so this provides
 * some fields to clarify what modification caused the error, if any. But it can also contain messages from any old
 * Exception (or other Throwable such as an Error) to report more unexpected kinds of errors.
 */
public class TaskError {

    public final String modificationId;
    public final String title;
    public final List<String> messages = new ArrayList<>();

    /** This constructor is used when an unexpected, unhandled error is encountered. */
    public TaskError(Throwable throwable) {
        this.modificationId = null;
        this.title = "Unhandled error: " + throwable.getClass().getSimpleName();
        this.messages.add(ExceptionUtils.shortCauseString(throwable));
        this.messages.add(ExceptionUtils.stackTraceString(throwable));
    }

    /**
     * This constructor is used for errors, warnings, or informational messages that occur
     * while applying a scenario to a network.
     */
    public TaskError(Modification modification, Collection<String> messages) {
        this.modificationId = modification.comment;
        this.title = "while applying the modification entitled '" + modification.comment + "'.";
        checkArgument(messages.size() <= Modification.MAX_MESSAGES + 1);
        this.messages.addAll(messages);
        this.messages.sort(String::compareTo);
    }

}
