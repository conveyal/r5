package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.ArrayList;
import java.util.List;

public interface ParetoFunction {
    void dominate(int v, int u, ParetoVectorDominator dominator);


    static Builder createParetoFunctions() {
        return new Builder();
    }

    class Builder {
        private List<ParetoFunction> list = new ArrayList<>();

        private Builder() {}

        public Builder lessThen() {
            list.add((v, u, dominator) -> dominator.applyDominance(v < u, u < v));
            return this;
        }
        public Builder lessThen(final int delta) {
            list.add((v, u, dominator) -> dominator.applyDominance(v + delta < u, u + delta < v));
            return this;
        }
        public Builder greaterThen() {
            list.add((v, u, dominator) -> dominator.applyDominance(v > u, u > v));
            return this;
        }
        public Builder different() {
            list.add((v, u, dominator) -> dominator.applyDominance(v != u, v != u));
            return this;
        }
        public ParetoFunction[] build() {
            return list.toArray(new ParetoFunction[0]);
        }
    }
}
