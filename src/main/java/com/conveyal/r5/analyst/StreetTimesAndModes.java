package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.util.Objects;

/// Contains a map from stop index to information about how that stop was reached. This includes the
/// mode by which it was reached and how long it took, as well as whether on-demand service was used
/// after the initial access leg. Includes utility methods for updating/processing this map.
public class StreetTimesAndModes {
    public TIntObjectMap<StreetTimeAndMode> streetTimesAndModes = new TIntObjectHashMap<StreetTimeAndMode>();

    public static class StreetTimeAndMode {
        public int time;
        public StreetMode mode;
        /// True when this street leg rides an on-demand service after the initial street mode.
        public boolean onDemand;

        public StreetTimeAndMode(int time, StreetMode mode) {
            this(time, mode, false);
        }

        public StreetTimeAndMode(int time, StreetMode mode, boolean onDemand) {
            this.time = time;
            this.mode = mode;
            this.onDemand = onDemand;
        }

        @Override
        public String toString(){
            String modeString = onDemand ? mode + "+ON_DEMAND" : mode.toString();
            return modeString + (" ") + (String.format("%.1f", time / 60.0)) + (" min.");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StreetTimeAndMode that = (StreetTimeAndMode) o;
            return time == that.time &&
                    mode == that.mode &&
                    onDemand == that.onDemand;
        }

        @Override
        public int hashCode() {
            return Objects.hash(time, mode, onDemand);
        }
    };

    /// Strips out mode information
    /// @return map from stop index to time
    public TIntIntMap getTimes() {
        TIntIntMap times = new TIntIntHashMap();
        streetTimesAndModes.forEachEntry((stop, timeAndMode) -> {
            times.put(stop, timeAndMode.time);
            return true; // Trove signal to continue iteration
        });
        return times;
    }

    /// Merges the supplied values with the ones in this map, keeping the value with the minimum time when keys collide.
    /// @param times map from stop index to clock time at which the stop was reached in a street search
    /// @param streetMode used to obtain these times
    /// @param onDemand whether these times were obtained using an on-demand service
    void update(TIntIntMap times, StreetMode streetMode, boolean onDemand) {
        times.forEachEntry((stop, time) -> {
            if (!streetTimesAndModes.containsKey(stop) || time < streetTimesAndModes.get(stop).time) {
                streetTimesAndModes.put(stop, new StreetTimeAndMode(time, streetMode, onDemand));
            }
            return true;
        });
    }

}
