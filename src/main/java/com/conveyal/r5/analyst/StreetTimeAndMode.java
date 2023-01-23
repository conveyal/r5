package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;

import java.util.Objects;

public class StreetTimeAndMode {
    public final int time;
    public final StreetMode mode;

    public StreetTimeAndMode(int time, StreetMode mode) {
        this.time = time;
        this.mode = mode;
    }

    @Override
    public String toString() {
        return mode.toString() + (" ") + (String.format("%.1f", time / 60.0)) + (" min.");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreetTimeAndMode that = (StreetTimeAndMode) o;
        return time == that.time &&
                mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, mode);
    }
}
