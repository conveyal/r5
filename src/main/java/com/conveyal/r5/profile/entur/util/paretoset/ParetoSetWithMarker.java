package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.stream.Stream;


/**
 * {@link ParetoSet} with the possibility to set a index marker, which
 * can be used to list all elements added after the marker is set.
 *
 *
 * @param <T> the element type
 */
public class ParetoSetWithMarker<T> extends ParetoSet<T> {
    private int marker = 0;


    public ParetoSetWithMarker(ParetoComparator<T> comparator) {
        super(comparator);
    }

    @Override
    public void clear() {
        super.clear();
        marker = 0;
    }

    /**
     * Create a stream for all elements added after the marker.
     */
    public Stream<T> streamAfterMarker() {
        return stream(marker);
    }

    /**
     * Move the marker after the last element in the set.
     */
    public void markAtEndOfSet() {
        marker = size();
    }

    @Override
    protected void notifyReindex(int fromIndex, int toIndex) {
        if(fromIndex == marker) {
            marker = toIndex;
        }
    }
}
