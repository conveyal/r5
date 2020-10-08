package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;

/**
 * A transfer allowance for NYC. This has a bunch of sub-transfer TODO clarify this comment.
 */

public class NYCTransferAllowance extends TransferAllowance {
    // all these are public so they can be JSON-serialized for the debug interface

    public final LIRRTransferAllowance lirr;
    public final NYCPatternType metrocardTransferSource;
    public final int metrocardTransferExpiry;

    public boolean inSubwayPaidArea;

    /** Where the Metro-North was boarded, -1 if not on Metro-North */
    public final int metroNorthBoardStop;

    /** Direction of the current Metro-North trip, -1 if not on Metro-North */
    public final int metroNorthDirection;

    /** Whether the current Metro-North trip is peak or not */
    public final boolean metroNorthPeak;

    /** Since metro-north doesn't allow inter-line transfers, record which line we are on */
    public final NYCInRoutingFareCalculator.MetroNorthLine metroNorthLine;

    /**
     * Whether the current Metro-North trip is on the New Haven line
     * This is important because there are no free transfers between the New Haven and Harlem/Hudson
     * lines, see http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/
     */

    public NYCTransferAllowance(LIRRTransferAllowance lirr, NYCPatternType metrocardTransferSource,
                                int metrocardTransferExpiry, boolean inSubwayPaidArea,
                                int metroNorthBoardStop, int metroNorthDirection, boolean metroNorthPeak,
                                NYCInRoutingFareCalculator.MetroNorthLine metroNorthLine) {
        // only the value needs to be set correctly. The expiration time and number of transfers left are only used in
        // the second domination rule, which we override in atLeastAsGoodForAllFutureRedemptions
        // value is set to $100,000 - this effectively disables Theorem 3.1 from the paper
        // (https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf)
        // Theorem 3.1 depends on nonnegativity of transfer allowances, which we cannot guarantee in New York -
        // see comments about Metro-North below. This is fine for algorithm correctness, although it means retaining
        // more trips than necessary. You can think of it as a ghost transfer to a (very expensive) transit service that
        // will never be optimal. Virgin Galactic flights are $250,000, or $150,000 with your LIRR ticket, etc.
        // Theorem 3.2 does not depend on assumptions about nonnegativity of transfer allowances, as long as negative
        // transfer allowances are considered in atLeastAsGoodForAllFutureRedemptions.
        super(100_000_00, Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.lirr = lirr;
        this.metrocardTransferSource = metrocardTransferSource;
        this.metrocardTransferExpiry = metrocardTransferExpiry;
        this.inSubwayPaidArea = inSubwayPaidArea;
        this.metroNorthBoardStop = metroNorthBoardStop;
        this.metroNorthDirection = metroNorthDirection;
        this.metroNorthPeak = metroNorthPeak;
        this.metroNorthLine = metroNorthLine;
    }

    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        // Note that we're not calling super here, all logic is reimplemented here.
        // This goes through lots of ways other could be better than this, and returns false if
        // other is possibly better in any way.
        if (other instanceof NYCTransferAllowance) {
            NYCTransferAllowance o = (NYCTransferAllowance) other;
            // if this LIRR is not at least as good as other for all future redemptions, this NYCTransferAllowance
            // is not as good as or better than the other for all future redemptions
            if (lirr != null && !lirr.atLeastAsGoodForAllFutureRedemptions(o.lirr)) return false;
            // If the other has an LIRR transfer allowance and this doesn't, other could be better for some destinations
            // However, if both are null, they are equivalent
            if (lirr == null && o.lirr != null) return false;

            // if they don't have the same metrocard transfer source, retain both -- different transfer rules
            // exception: if other does not have a metrocard transfer source, it can't be better on this criterion
            // This is not strictly true, consider a local bus ->express bus -> express bus trip and a local bus -> local bus ->
            // express bus -> express bus trip. After the local buses, the first one has a better transfer allowance, and the same
            // cumulative fare, but will result in a more expensive trip. But we have decided not to support this type of
            // negativity of transfer allowances, where throwaway buses are ridden only to reduce the total fare.
            if (metrocardTransferSource != o.metrocardTransferSource && o.metrocardTransferSource != null) return false;

            // If the other expires later, it could be better
            if (metrocardTransferExpiry < o.metrocardTransferExpiry) return false; // expires sooner

            // if the other is in the subway and this is not, other could be better. All else equal, it is always better
            // to be in the subway than not in the subway - because leaving the subway is free.
            if (!inSubwayPaidArea && o.inSubwayPaidArea) return false; // free transfer with other and not with this

            // if other does not have a Metro-North allowance, it could be better - particularly when peak/off-peak
            // is involved. It may be valuable to board further down the line, if you can avoid a peak fare.
            // Consider a trip from Manhattan to White Plains, under the City Fare scenario. Say one option is to take
            // Metro-North from Grand Central to Botanical Garden - this would cost 2.75 under the city fare scenario.
            // Another option is to take the subway - this would also cost 2.75. At Botanical Garden, the Metro-North
            // service would be considered superior, because it has a Metro-North transfer allowance and the subway does
            // not. But actually, if you then board another Metro-North train to White Plains, the router will extend
            // the trip (rather than allowing you to buy two tickets) and the total fare will be 12.75, whereas if you
            // switched to Metro-North from the subway the fare would only be 2.75 + 4.25 = $7. But at the intermediate
            // point the Metro-North prefix was considered preferable.
            // otherwise, this is only the same or better if board stops, peak/offpeak,
            // directions, and line are all the same. This will overretain trips, but Metro-North
            // is small enough this should be fine.
            if (metroNorthBoardStop != o.metroNorthBoardStop ||
                            metroNorthPeak != o.metroNorthPeak ||
                            metroNorthDirection != o.metroNorthDirection ||
                            metroNorthLine != o.metroNorthLine
                    ) return false;

            // if we got here, we're good
            return true;
        } else {
            throw new IllegalArgumentException("Mixing of transfer allowance types!");
        }
    }

}
