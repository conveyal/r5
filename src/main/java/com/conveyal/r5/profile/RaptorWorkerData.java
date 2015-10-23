package com.conveyal.r5.profile;


import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransitLayer;
import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class RaptorWorkerData implements Serializable {
    public static final Logger LOG = LoggerFactory.getLogger(RaptorWorkerData.class);

    /** we use empty int arrays for various things, e.g. transfers from isolated stops. They're immutable so only use one */
    public static final int[] EMPTY_INT_ARRAY = new int[0];

    public final int nStops;

    public final int nPatterns;

    /** The number of targets (vertices or samples) */
    public final int nTargets;

    /** For every stop, one pair of ints (targetStopIndex, distanceMeters) for each transfer out of that stop. This uses 0-based stop indices that are specific to RaptorData */
    public final List<int[]> transfersForStop = new ArrayList<>();

    /** A list of pattern indexes passing through each stop, again using Raptor indices. */
    public final List<int[]> patternsForStop = new ArrayList<>();

    /** For each pattern, a 2D array of stoptimes for each trip on the pattern. */
    public List<RaptorWorkerTimetable> timetablesForPattern = new ArrayList<>();

    /** does this RaptorData have any scheduled trips? */
    public boolean hasSchedules = false;

    /** does this RaptorData have any frequency trips? */
    public boolean hasFrequencies = false;
    
    /**
     * Map from stops that do not exist in the graph but only for the duration of this search to their stop indices in the RAPTOR data.
     */
    //public TObjectIntMap<AddTripPattern.TemporaryStop> addedStops = new TObjectIntHashMap<>();

    /** Some stops may have special transfer rules. They are stored here. */
    public TIntObjectMap<List<RaptorWorkerTimetable.TransferRule>> transferRules = new TIntObjectHashMap<>();

    /** The transfer rules to be applied in the absence of another transfer rule */
    public List<RaptorWorkerTimetable.TransferRule> baseTransferRules = new ArrayList<>();

    /** The boarding assumption used for initial vehicle boarding, and for when there is no transfer rule defined */
    public RaptorWorkerTimetable.BoardingAssumption boardingAssumption;

    /**
     * For each stop, one pair of ints (targetID, distanceMeters) for each destination near that stop.
     * For generic TimeSurfaces these are street intersections. They could be anything though since the worker doesn't
     * care what the IDs stand for. For example, they could be point indexes in a pointset.
     */
    public final List<int[]> targetsForStop = new ArrayList<>();

    /** The 0-based RAPTOR indices of each stop from their vertex IDs */
    public transient final TIntIntMap indexForStop;
     /** Optional debug data: the name of each stop. */
    public transient final List<String> stopNames = new ArrayList<>();
    public transient final List<String> patternNames = new ArrayList<>();

    /**
     * This is an adapter constructor, which makes Analyst cluster worker data from a new-style TransportNetwork.
     * RaptorWorkers are (intentionally) very similar to the TransitLayer of TransportNetworks.
     * For now we'll just perform a copy, filtering out any unused patterns and trips.
     * TODO study the differences between the two, and merge the two data structures into one class.
     */
    public RaptorWorkerData (TransitLayer transitLayer, LinkedPointSet targets, LocalDate date) {
        if (targets != null && (targets.streetLayer != transitLayer.linkedStreetLayer)) {
            LOG.error("The supplied targets and the supplied transitLayer are not linked to the same streetLayer.");
        }
        // The RaptorWorkerData is a single-job throwaway item. It includes travel times (not distances, to avoid divides)
        // to a specific PointSet+SampleSet of interest in the task at hand, or to all the street vertices.
        nStops = transitLayer.getStopCount();
        nTargets = targets.size();
        nPatterns = transitLayer.tripPatterns.size();
        // Copy transfers table.
        transitLayer.transfersForStop.forEach(t -> transfersForStop.add(t.toArray()));
        transitLayer.patternsForStop.forEach(p -> patternsForStop.add(p.toArray()));
        // Copy contiguous zero-based array into a sparse map.
        indexForStop = new TIntIntHashMap();
        for (int s = 0; s < transitLayer.streetVertexForStop.size(); s++) {
            indexForStop.put(s, transitLayer.streetVertexForStop.get(s));
        }
        // TODO fn to Eval LinkedSampleSet WRT an SPT map vertex -> travel time. It should be OK to just iterate over the whole target set.
        // TODO can we just make stop trees on demand? We only need them when linking a target pointset to a TransitNetwork
        // TODO can we just link backward, from targets to stops? Then a LinkedPointSet can contain links to the street network and the transit stops. However not all target points are reached in a search so iterating this direction may be inefficient. For now I don't have that option, work with the existing data structures.
        this.targetsForStop.addAll(targets.stopTrees);

        // Copy new-style TransportNetwork TripPatterns to RaptorWorkerTimetables
        BitSet servicesActive = transitLayer.getActiveServicesForDate(date);
        for (com.conveyal.r5.transit.TripPattern tripPattern : transitLayer.tripPatterns) {
            // NOTE this is leaving all patterns with zero active trips in the list.
            // Otherwise we have to re-number all the patterns.
            RaptorWorkerTimetable timetable = tripPattern.toRaptorWorkerTimetable(servicesActive);
            timetablesForPattern.add(timetable);
        }
        // Currently we only support scheduled services
        hasSchedules = true;
        hasFrequencies = false;
    }

    // At first we're going to use the street vertices as targets and try to just do isochrones.

}
