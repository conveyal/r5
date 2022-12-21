package com.conveyal.r5.analyst.progress;

/**
 * For classes that support progress listeners but may not always use one, to avoid littering the code with null checks
 * we have this trivial ProgressListener implementation that does nothing.
 */
public class NoopProgressListener implements ProgressListener {

    @Override
    public void beginTask(String description, int totalElements) { }

    @Override
    public void increment() { }

    @Override
    public void increment (int n) { }

}
