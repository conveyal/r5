package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.Collection;

public class DropEventAdapter<T> implements ParetoSetEventListener<T> {
    private DropEventListener<T> delegate;

    public DropEventAdapter(DropEventListener<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void notifyElementAccepted(T newElement, Collection<? extends T> resultSet) {

    }

    @Override
    public void notifyElementDropped(T element, T droppedByElement) {
        this.delegate.elementDroppedFromParetoSet(droppedByElement, element);
    }

    @Override
    public void notifyElementRejected(T element, Collection<? extends T> existingSet) {

    }
}
