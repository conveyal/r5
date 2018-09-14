package com.conveyal.r5.profile.entur.util;

public interface ParetoDominanceFunction {
    boolean dominates(int v, int u);
    boolean mutualDominance(int v, int u);
}
