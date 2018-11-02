package com.conveyal.r5.profile.entur.api;


/**
 * Iterator for fast iteration over unsigned integers.
 * <p/>
 * <pre>
 * UnsignedIntIterator it = ..;
 *
 * for (int i = it.next(); i != -1; i = it.next()) {
 *     ...
 * }
 * </pre>
 */
public interface UnsignedIntIterator {
    /**
     * Retrieve the next unsigned int, if no more elements is available {@code -1} is returned.
     */
    int next();
}
