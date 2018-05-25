package com.conveyal.r5.util;

import java.util.ArrayList;
import java.util.List;

public enum ParetoDominateFunction {
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
    DIFFRENT {
        @Override public final boolean dominates(int v, int u) {
            return v != u;
        }
        @Override public boolean mutualDominance(int v, int u) { return v != u; }
    };
    public abstract boolean dominates(int v, int u);
    public abstract boolean mutualDominance(int v, int u);

    public static Builder createParetoDominanceFunctionArray() {
        return new Builder();
    }

    public static class Builder {
        private List<ParetoDominateFunction> list = new ArrayList<>();

        private Builder() {}

        public Builder lessThen() {
            list.add(LESS_THEN);
            return this;
        }
        public Builder greaterThen() {
            list.add(GRATER_THEN);
            return this;
        }
        public Builder different() {
            list.add(DIFFRENT);
            return this;
        }

        public ParetoDominateFunction[] build() {
            return list.toArray(new ParetoDominateFunction[list.size()]);
        }
    }
}
