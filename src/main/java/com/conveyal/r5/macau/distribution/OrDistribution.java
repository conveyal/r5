package com.conveyal.r5.macau.distribution;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The probability that any of N mutually exclusive events.
 * This comes out to a chain of operations A + B - (A * B) or equivalently A + (1 - A) * B.
 * That is, the probability that A happens or that B happens, minus the probability that they both happen at once.
 * Equivalently, the probability that A happens, or if it doesn't happen, then probability that B happens.
 * On a graph, imagine B scaled to fit into the leftover space between A and 1.
 * This can apply to a probability mass function P(x) or cumulative function P(X<x), since both return probabilities.
 *
 * At each round, we wrap or copy the OrDistributions at the reached stops in the previous round.
 * At each departure minute, we wrap or copy the OrDistributions at the stops reached in the later departure minute.
 * These serve the same role as pareto sets, or minimum operations on integer times in normal Raptor.
 * We want to copy them instead of wrapping, to avoid "deep wrapping" 120 layers deep or more.
 */
public class OrDistribution implements Distribution {

    private final List<Distribution> distributions = new ArrayList<>();

    // Associativity optimization, merge terms into this existing OrDistribution
    // TODO verify correctness
    public boolean addAndPrune (OrDistribution orDistribution) {
        boolean updated = false;
        for (Distribution term : orDistribution.distributions) {
            updated |= addAndPrune(term);
        }
        return updated;
    }

    /**
     * Pruning refers to removing distributions that can't affect the result. We don't keep the set of removed
     * distributions so this is non-reversible, i.e. we can progressively add distributions but not remove them.
     * Not threadsafe for now, do not call from multiple threads.
     *
     * @return true if the new distribution was retained
     */
    public boolean addAndPrune (Distribution candidate) {
        if (candidate.minTime() > 120*60) return false;
        // First check if any existing distribution beats the candidate
        for (Distribution existing : distributions) {
            if (canPrune(candidate, existing)) {
                return false;
            }
        }
        // If the candidate is retained, now see if it kicks out any existing distributions.
        Iterator<Distribution> iterator = distributions.iterator();
        while (iterator.hasNext()) {
            Distribution existing = iterator.next();
            if (canPrune(existing, candidate)) {
                iterator.remove();
            }
        }
        // Now add the retained candidate to the list and signal the caller that it was retained.
        distributions.add(candidate);
        return true;
    }

    /**
     * Some distributions will not, specifically those that only rise above zero to the right of some other distribution
     * in the set reaching 1.
     *
     * @return true if candidate cannot contribute to the final distribution, considering existing one.
     */
    private boolean canPrune (Distribution candidate, Distribution existing) {
        if (existing.maxTime() < candidate.minTime()) return true;
        return false;
    }

    public OrDistribution copy () {
        OrDistribution copy = new OrDistribution();
        // Assume optimal pruning was actively maintained on list within source distribution object.
        // A copy constructor initializing the list directly from the source list would be more efficient.
        copy.distributions.addAll(distributions);
        return copy;
    }

    @Override
    public int minTime () {
        return distributions.stream().mapToInt(Distribution::minTime).min().getAsInt();
    }

    @Override
    public int maxTime () {
        // TODO should filter for finite maxTimes or max cumulative probability less than one.
        // Empty list will throw exception, which is good as fail-fast behavior.
        return distributions.stream().mapToInt(Distribution::maxTime).min().getAsInt();
    }

    @Override
    public double maxCumulativeProbability () {
        throw new UnsupportedOperationException();
    }

    @Override
    public double probabilityDensity (int t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double cumulativeProbability (int t) {
        throw new UnsupportedOperationException();
    }

    /** Make a semi-shallow protective copy with all terms shifted to the right by the same amount. */
    public OrDistribution rightShiftTerms (int shiftAmount) {
        OrDistribution result = new OrDistribution();
        for (Distribution distribution : distributions) {
            result.distributions.add(distribution.rightShift(shiftAmount));
        }
        return result;
    }

}
