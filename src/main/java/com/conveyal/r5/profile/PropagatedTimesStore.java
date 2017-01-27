package com.conveyal.r5.profile;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.ResultSet;
import com.conveyal.r5.analyst.cluster.ResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.commons.math3.util.FastMath.toRadians;

/**
 * This is an exact copy of PropagatedTimesStore that's being modified to work with (new) TransitNetworks
 * instead of (old) Graphs. We can afford the maintainability nightmare of duplicating so much code because this is
 * intended to completely replace the old class soon.
 * Its real purpose is to make something like a PointSetTimeRange out of many RAPTOR runs using the same PointSet as destinations.
 *
 * Stores travel times propagated to the search targets (a set of points of interest in a PointSet)
 * in one-to-many repeated raptor profile routing.
 *
 * Each RAPTOR call finds minimum travel times to each transit stop. Those must be propagated out to the final targets,
 * giving minimum travel times to each target (destination opportunity) in accessibility analysis.
 * Those results for one call are then merged into the summary statistics per target over the whole time window
 * (over many RAPTOR calls). This class handles the storage and merging of those summary statistics.
 *
 * Currently this includes minimum, maximum, and average earliest-arrival travel time for each target.
 * We could conceivably retain all the propagated times for every departure minute instead of collapsing them down into
 * three numbers. If they were all sorted, we could read off any quantile, including the median travel time.
 * Leaving them in departure time order would also be interesting, since you could then see visually how the travel time
 * varies as a function of departure time.
 *
 * We could also conceivably store travel time histograms per destination, but this entails a loss of information due
 * to binning into minutes. These binned times could not be used to apply a smooth sigmoid cutoff which we usually
 * do at one-second resolution. However, the data in the seconds portion is mostly random, so we could achieve roughly
 * the same effect by jittering the stored values when applying the cutoff.
 *
 * When exploring single-point (one-to-many) query results it would be great to have all these stored or produced on
 * demand for visualization.
 *
 * TODO this class should probably be eliminated in r5 2.0 as we don't really do much with average travel times anymore.
 */
public class PropagatedTimesStore {

    private static final Logger LOG = LoggerFactory.getLogger(PropagatedTimesStore.class);

    int size;
    int[] avgs;

    // number of times to bootstrap the mean.
    public final int N_BOOTSTRAPS = 400;

    private static final Random random = new Random();

    public PropagatedTimesStore(int size) {
        this.size = size;
        avgs = new int[size];
        Arrays.fill(avgs, Integer.MAX_VALUE);
    }

    /**
     * @param times for search (varying departure time), an array of travel times to each transit stop.
     */
    public void setFromArray(int[][] times, float reachabilityThreshold) {
        if (times.length == 0)
            // nothing to do
            return;

        // assume array is rectangular
        int stops = times[0].length;

        // cache random numbers. This should be fine as we're mixing it with the number of minutes
        // at which each destination is accessible, which is sometimes not 120, as well as the stop
        // position in the list (note that we have cleverly chosen a number which is a prime
        // so is not divisible by the number of iterations on the bootstrap). Finally recall that
        // the maximum number of times we're sampling from is generally 120 and we modulo this,
        // so the pigeonhole principle applies.
        // this is effectively a "random number generator" with phase 10007
        int[] randomNumbers = random.ints().limit(10007).map(Math::abs).toArray();
        int nextRandom = 0;

        // use the cardinality of includeInAverages because some of the times are never included in averages
        // and thus don't count towards meeting the reachability threshold.
        // minCount should always be at least 1 even when reachability threshold is 0, because if a point is never
        // reached it certainly shouldn't be included in the averages.
        int minCount = Math.max((int) (times.length * reachabilityThreshold), 1);

        // loop over stops on the outside so we can bootstrap
        STOPS: for (int stop = 0; stop < stops; stop++) {
            // compute the average
            int sum = 0;
            int count = 0;

            TIntList timeList = new TIntArrayList();

            ITERATIONS: for (int i = 0; i < times.length; i++) {
                if (times[i][stop] == RaptorWorker.UNREACHED)
                    continue ITERATIONS;

                // don't include extrema in averages
                sum += times[i][stop];
                count++;

                timeList.add(times[i][stop]);
            }

            if (count < minCount)
                continue STOPS;

            avgs[stop] = sum / count;
        }
    }

    /**
     * Make a ResultEnvelope directly from a given SampleSet.
     * The RaptorWorkerData must have been constructed from the same SampleSet.
     * This is how the accumulated results are returned back out to the PropagatedTimesStore's creator.
     */
    public ResultEnvelope makeResults(PointSet pointSet, boolean includeTimes, boolean includeHistograms, boolean includeIsochrones) {
        ResultEnvelope envelope = new ResultEnvelope();
        envelope.avgCase   = new ResultSet(avgs, pointSet, includeTimes, includeHistograms, includeIsochrones);
        return envelope;
    }

    public int countTargetsReached() {
        int count = 0;
        for (int avg : avgs) {
            if (avg != RaptorWorker.UNREACHED) {
                count++;
            }
        }
        return count;
    }
}
