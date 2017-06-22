package com.conveyal.r5.profile;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * An implementation of DominatingList that conserves some sub-optimal states to obtain a wider variety of paths.
 * This is used for customer-facing routing (e.g. in Modeify) where people might actually want to take a path that is
 * technically suboptimal for some personal reason.
 *
 * We may also end up using this to establish choice sets in traffic modeling. See dominates() function below.
 */
public class SuboptimalDominatingList implements DominatingList {
    public SuboptimalDominatingList (int suboptimalMinutes) {
        this.suboptimalSeconds = suboptimalMinutes * 60;
    }

    /** The best time of any state in this bag */
    public int bestTime = Integer.MAX_VALUE;

    /** the number of seconds a state can be worse without being dominated. */
    public int suboptimalSeconds;

    private List<McRaptorSuboptimalPathProfileRouter.McRaptorState> list = new LinkedList<>();

    public boolean add (McRaptorSuboptimalPathProfileRouter.McRaptorState newState) {
        // apply strict dominance if there is a state at the previous round on the same previous pattern arriving at this
        // stop (prevents reboarding/hopping between routes on common trunks)
        // For example, consider the red line in DC, which runs from Shady Grove to Glenmont. At rush hour, every other
        // train is a short-turn pattern running from Grosvenor to Silver Spring. While these are clearly separate
        // patterns, there's never a reason to get off the Silver Spring train and get on the Glenmont train, unless
        // you want to go past Silver Spring. These trains are running every two minutes, so you can jump between them
        // a few times before the suboptimal dominance will cut off the search.

        // We don't just want to cut off switching to another vehicle on the same route direction; consider the Rush+
        // yellow line in DC. One pattern runs from Fort Totten to Huntington, while the other runs from Greenbelt
        // to Franconia-Springfield. Suppose you wanted to go from Greenbelt to Huntington. It would make complete sense
        // to transfer from one yellow line train to another.

        // We also don't want to cut off switching between vehicles on common trunks. Consider the L2 and the Red Line
        // in DC, which both serve Connecticut Ave between Van Ness-UDC and Farragut Square. The Red Line is much faster
        // so it makes sense to transfer to it if you get on the L2 somewhere outside the common trunk.

        // In this particular case, the L2 and the red line don't serve the same stops; however, this is still important.
        // Suppose you wanted to get on the circulator, which meets the red line and L2 at Woodley Park. It could make
        // sense to take L2 -> Red -> Circulator, even though the L2 would have taken you all the way. That's why we
        // apply strict dominance, rather than just forbidding this situation.

        // We only look back one pattern; the reason for this is that we want to avoid a lot of looping in a function
        // that gets called a lot, and it seems unlikely that there would be time to take two other patterns and still
        // slip into the window of suboptimality. I haven't tested it though to see its effect on response times.
//        if (state.pattern != -1 && state.patterns.length > 1) {
//            for (McRaptorSuboptimalPathProfileRouter.McRaptorState s : list) {
//                if (s.round == state.round - 1 && s.pattern == state.patterns[s.round - 1] && s.time <= state.time) {
//                    return false;
//                }
//            }
//        }

        for (Iterator<McRaptorSuboptimalPathProfileRouter.McRaptorState> it = list.iterator(); it.hasNext(); ) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState oldState = it.next();

            if (dominates(oldState, newState)) return false;
            if (dominates(newState, oldState)) it.remove();
        }

        // Update the best time at this location to reflect the new state.
        if (newState.time < bestTime) bestTime = newState.time;

        // The new state is non-dominated. Keep it.
        list.add(newState);

        return true;
    }

    /**
     * This function is the core logic that makes it different from "normal" Raptor, and should be explained should we
     * want to document how R5 finds sub-optimal paths in detail.
     * TODO ADD explanation of why we're running multiple access mode searches at once
     */
    public boolean dominates (McRaptorSuboptimalPathProfileRouter.McRaptorState newState, McRaptorSuboptimalPathProfileRouter.McRaptorState oldState) {
        boolean sameAccessMode = oldState.accessMode == newState.accessMode;

        // If there is any way to reach this location with less rides and the same or less time, throw away the old state
        // iff they used the same access mode.
        if (sameAccessMode && newState.round < oldState.round && newState.time <= oldState.time) return true;

        // looser dominance rules for states with different access modes
        // this is more efficient than what we used to do, which was to treat different access modes as completely incomparable
        // this eliminates a lot of trips that drive out into the sticks and take transit back in, which are slow to compute
        // TODO this *5 nonsense is a huge clooge. Make separate fields for suboptimalSecondsSameMode and suboptimalSecondsDifferentMode.
        int threshold = sameAccessMode ? suboptimalSeconds : suboptimalSeconds * 5;

        if (newState.time + threshold < oldState.time) return true;

        return false;
    }

    public Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates () {
        // We used to prune here, but now we prune on add.
        // I've observed in the past that pruning on add slows the algorithm down due to all of the looping.
        // I also tried pruning once per round, but that also slows the algorithm down (perhaps because it's doing
        // so many pairwise comparisons).
        return list;
    }
}
