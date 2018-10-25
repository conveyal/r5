package com.conveyal.r5.profile.entur.util.paretoset;

public interface InternalParetoDominanceFunction {
    boolean dominates(int v, int u);
    boolean mutualDominance(int v, int u);
}
