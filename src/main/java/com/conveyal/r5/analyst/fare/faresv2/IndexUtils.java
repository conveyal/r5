package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.map.TIntObjectMap;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

/** Utility funtions for indexing using RoaringBitmaps */
public class IndexUtils {
    /**
     * Get all rules that match indices, either directly or because that field was left blank.
     * Combine those rules into a single RoaringBitmap. Used for instance with fareLegRulesForFareAreaId in TransitLayer,
     * passing in a collection fare area indices, and returning a RoaringBitmap of all FareLegRules that match any of those
     * indices.
     */
    public static RoaringBitmap getMatching (TIntObjectMap<RoaringBitmap> rules, TIntCollection indices) {
        RoaringBitmap ret = new RoaringBitmap();
        for (TIntIterator it = indices.iterator(); it.hasNext();) {
            int index = it.next();
            if (rules.containsKey(index)) ret.or(rules.get(index));
        }
        if (rules.containsKey(TransitLayer.FARE_ID_BLANK)) ret.or(rules.get(TransitLayer.FARE_ID_BLANK));
        return ret;
    }

    /** Get all rules that match indices, either directly or because that field was left blank */
    public static RoaringBitmap getMatching (TIntObjectMap<RoaringBitmap> rules, RoaringBitmap indices) {
        RoaringBitmap ret = new RoaringBitmap();
        for (PeekableIntIterator it = indices.getIntIterator(); it.hasNext();) {
            int index = it.next();
            if (rules.containsKey(index)) ret.or(rules.get(index));
        }
        if (rules.containsKey(TransitLayer.FARE_ID_BLANK)) ret.or(rules.get(TransitLayer.FARE_ID_BLANK));
        return ret;
    }

    /** Get all rules that match index, either directly or because that field was left blank */
    public static RoaringBitmap getMatching (TIntObjectMap<RoaringBitmap> rules, int index) {
        RoaringBitmap ret = new RoaringBitmap();
        if (rules.containsKey(index)) ret.or(rules.get(index));
        if (rules.containsKey(TransitLayer.FARE_ID_BLANK)) ret.or(rules.get(TransitLayer.FARE_ID_BLANK));
        return ret;
    }
}
