package com.conveyal.r5.profile;

import java.util.Arrays;

/**
 * Different equals and hashCode function then Path
 *
 * It compares only boardStops and alightStops of provided Path in equals and hashCode
 * It is used to see which boardStop alightStop combination already exists in response so that
 * each combination is inserted only once
 */
public class HashPath  {
    final public Path path;

    public HashPath(Path path) {
        this.path = path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        HashPath hashPath = (HashPath) o;
        return this == hashPath || Arrays.equals(path.boardStops, hashPath.path.boardStops) && Arrays.equals(path.alightStops, hashPath.path.alightStops);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(path.boardStops) + 2 * Arrays.hashCode(path.alightStops);
    }
}
