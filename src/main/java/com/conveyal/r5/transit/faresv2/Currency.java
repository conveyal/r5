package com.conveyal.r5.transit.faresv2;

import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;

public class Currency {
    /** Map from currency code to what to multiply by to convert to fixed-point values */
    public static final TObjectIntMap<String> scalarForCurrency = new TObjectIntHashMap<>();
    static {
        scalarForCurrency.put("USD", 100);
        scalarForCurrency.put("CAD", 100);
    }
}
