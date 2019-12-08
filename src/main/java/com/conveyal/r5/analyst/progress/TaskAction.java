package com.conveyal.r5.analyst.progress;

/**
 * This represents the work actually carried out by a Task.
 * It's a single-method interface so it can be defined with lambda functions, or other objects can implement it.
 * When the action is run, it will receive an object implementing an interface through which it can report progress
 * and errors.
 */
public interface TaskAction {

    /**
     * This method will define an asynchronous action to take.
     * The parameter is a simpler interface of Task that only allows progress reporting, to encapsulate actions and
     * prevent them from seeing or modifying the task hierarchy that triggers and manages them.
     */
    public void action (ProgressListener progressListener);

}
