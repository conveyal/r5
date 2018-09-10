package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.mcrr.api.Pattern;
import com.conveyal.r5.profile.mcrr.api.TimeToStop;
import com.conveyal.r5.profile.mcrr.api.TransitDataProvider;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.Collections.emptyList;

public class TransitLayerRRDataProvider implements TransitDataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TransitLayerRRDataProvider.class);
    private static boolean PRINT_REFILTERING_PATTERNS_INFO = true;

    private TransitLayer transitLayer;

    /** Array mapping from original pattern indices to the filtered scheduled indices */
    private int[] scheduledIndexForOriginalPatternIndex;

    /** Schedule-based trip patterns running on a given day */
    private TripPattern[] runningScheduledPatterns;

    /** Map from internal, filtered pattern indices back to original pattern indices for scheduled patterns */
    private int[] originalPatternIndexForScheduledIndex;

    /** Services active on the date of the search */
    private final BitSet servicesActive;

    /** Allowed transit modes */
    private final EnumSet<TransitModes> transitModes;

    private final int walkSpeedMillimetersPerSecond;


    public TransitLayerRRDataProvider(TransitLayer transitLayer, LocalDate date, EnumSet<TransitModes> transitModes, float walkSpeedMetersPerSecond) {
        this.transitLayer = transitLayer;
        this.servicesActive  = transitLayer.getActiveServicesForDate(date);
        this.transitModes = transitModes;
        this.walkSpeedMillimetersPerSecond = (int)(walkSpeedMetersPerSecond * 1000f);
    }

    @Override
    public Iterable<TimeToStop> getTransfers(int stop) {
        TIntList m = transitLayer.transfersForStop.get(stop);

        if(m == null) {
            return emptyList();
        }

        List<TimeToStop> stopTimes = new ArrayList<>(m.size());

        for(int i=0; i<m.size();) {
            int toStop = m.get(i);
            ++i;
            int walkTime = m.get(i) / walkSpeedMillimetersPerSecond;
            ++i;

            if (walkTime < 0) {
                throw new IllegalStateException("Negative transfer time!!");
            }
            stopTimes.add(new TimeToStop(toStop, walkTime));
        }
        return stopTimes;
    }

    @Override
    public boolean skipCalendarService(int serviceCode) {
        return !servicesActive.get(serviceCode);
    }

    /** Prefilter the patterns to only ones that are running */
    public void init() {
        TIntList scheduledPatterns = new TIntArrayList();
        scheduledIndexForOriginalPatternIndex = new int[transitLayer.tripPatterns.size()];
        Arrays.fill(scheduledIndexForOriginalPatternIndex, -1);

        int patternIndex = -1; // first increment lands at 0
        int scheduledIndex = 0;

        for (TripPattern pattern : transitLayer.tripPatterns) {
            patternIndex++;
            RouteInfo routeInfo = transitLayer.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);
            if (pattern.servicesActive.intersects(servicesActive) && transitModes.contains(mode)) {
                // at least one trip on this pattern is relevant, based on the profile request's date and modes
                if (pattern.hasSchedules) { // NB not else b/c we still support combined frequency and schedule patterns.
                    scheduledPatterns.add(patternIndex);
                    scheduledIndexForOriginalPatternIndex[patternIndex] = scheduledIndex++;
                }
            }
        }

        originalPatternIndexForScheduledIndex = scheduledPatterns.toArray();

        runningScheduledPatterns = IntStream.of(originalPatternIndexForScheduledIndex)
                .mapToObj(transitLayer.tripPatterns::get).toArray(TripPattern[]::new);

        if (PRINT_REFILTERING_PATTERNS_INFO) {
            LOG.info("Prefiltering patterns based on date active reduced {} patterns to {} scheduled patterns",
                    transitLayer.tripPatterns.size(), scheduledPatterns.size());
            PRINT_REFILTERING_PATTERNS_INFO = false;
        }
    }

    @Override public Iterator<Pattern> patternIterator(BitSetIterator stops) {
        return new InternalPatternIterator(getPatternsTouchedForStops(stops));
    }

    /**
     * TODO TGR - Verify JavaDoc make sence
     * Get a list of the internal IDs of the patterns "touched" using the given index (frequency or scheduled)
     * "touched" means they were reached in the last round, and the index maps from the original pattern index to the
     * local index of the filtered patterns.
     */
    private BitSet getPatternsTouchedForStops(BitSetIterator stops) {
        BitSet patternsTouched = new BitSet();

        for (int stop = stops.next(); stop >= 0; stop = stops.next()) {
            getPatternsForStop(stop).forEach(originalPattern -> {
                int filteredPattern = scheduledIndexForOriginalPatternIndex[originalPattern];

                if (filteredPattern < 0) {
                    return true; // this pattern does not exist in the local subset of patterns, continue iteration
                }

                patternsTouched.set(filteredPattern);
                return true; // continue iteration
            });
        }
        return patternsTouched;
    }

    private TIntList getPatternsForStop(int stop) {
        return transitLayer.patternsForStop.get(stop);
    }

    class InternalPatternIterator implements Pattern, Iterator<Pattern> {
        private int nextPatternIndex;
        private int originalPatternIndex;
        private BitSet patternsTouched;
        private TripPattern pattern;

        InternalPatternIterator(BitSet patternsTouched) {
            this.patternsTouched = patternsTouched;
            this.nextPatternIndex = patternsTouched.isEmpty() ? -1 : 0;
        }

        /*  PatternIterator interface implementation */

        @Override public boolean hasNext() {
            return nextPatternIndex >=0;
        }

        @Override public Pattern next() {
            pattern = runningScheduledPatterns[nextPatternIndex];
            originalPatternIndex = originalPatternIndexForScheduledIndex[nextPatternIndex];
            nextPatternIndex = patternsTouched.nextSetBit(nextPatternIndex + 1);
            return this;
        }


        /*  Pattern interface implementation */

        @Override public int originalPatternIndex() {
            return originalPatternIndex;
        }

        @Override
        public int currentPatternStop(int stopPositionInPattern) {
            return pattern.stops[stopPositionInPattern];
        }

        @Override
        public int currentPatternStopsSize() {
            return pattern.stops.length;
        }

        @Override
        public int getTripSchedulesIndex(TripSchedule schedule) {
            return pattern.tripSchedules.indexOf(schedule);
        }

        @Override
        public TripSchedule getTripSchedule(int index) {
            return pattern.tripSchedules.get(index);
        }

        @Override
        public int getTripScheduleSize() {
            return pattern.tripSchedules.size();
        }
    }
}
