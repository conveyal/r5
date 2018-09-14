package com.conveyal.r5.profile.entur.util;

import java.util.ArrayList;
import java.util.List;

public enum ParetoDominanceFunctions implements ParetoDominanceFunction {
    LESS_THEN {
        @Override public final boolean dominates(int v, int u) {
            return v < u;
        }
        @Override public boolean mutualDominance(int v, int u) { return false; }
    },
    GRATER_THEN {
        @Override public final boolean dominates(int v, int u) {
            return u  < v;
        }
        @Override public boolean mutualDominance(int v, int u) { return false; }
    },
    DIFFERENT {
        @Override public final boolean dominates(int v, int u) {
            return v != u;
        }
        @Override public boolean mutualDominance(int v, int u) { return v != u; }
    };

    public static Builder createParetoDominanceFunctionArray() {
        return new Builder();
    }

    public static class Builder {
        private List<ParetoDominanceFunction> list = new ArrayList<>();

        private Builder() {}

        public Builder lessThen() {
            list.add(LESS_THEN);
            return this;
        }
        public Builder lessThen(int delta) {
            list.add(new LessThenDelta(delta));
            return this;
        }
        public Builder greaterThen() {
            list.add(GRATER_THEN);
            return this;
        }
        public Builder different() {
            list.add(DIFFERENT);
            return this;
        }

        public ParetoDominanceFunction[] build() {
            return list.toArray(new ParetoDominanceFunction[list.size()]);
        }
    }


    static class LessThenDelta implements ParetoDominanceFunction {
        private final int delta;

        LessThenDelta(int delta) {
            this.delta = delta;
        }
        @Override public final boolean dominates(int v, int u) { return v < (u + delta); }
        @Override public boolean mutualDominance(int v, int u) { return false; }
    }
}
