package com.conveyal.r5.profile;

import java.util.Arrays;

/**
 * A wrapper around a Path that redefines the hash code and equals functions.
 * The functions are changed to compare only the boardStops and alightStops of the Path.
 * This is used to group paths by their combination of boardStops and alightStops in the response.
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
        return this == hashPath || Arrays.equals(path.boardStops, hashPath.path.boardStops)
                && Arrays.equals(path.alightStops, hashPath.path.alightStops);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(path.boardStops) + 2 * Arrays.hashCode(path.alightStops);
    }
}
