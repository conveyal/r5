package com.conveyal.data.geobuf;

import geobuf.Geobuf;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * Decode a Geobuf.
 */
public class GeobufDecoder implements Iterator<GeobufFeature> {
    private int maxFeat, i = 0;

    private Geobuf.Data.FeatureCollection featureCollection;

    private List<String> keys;

    private double precisionDivisor;

    /** Create a Geobuf decoder, optionally backed by high-performance on-disk storage */
    public GeobufDecoder (InputStream is) throws IOException {
        // read everything into memory
        Geobuf.Data data = Geobuf.Data.parseFrom(is);
        keys = data.getKeysList();
        featureCollection = data.getFeatureCollection();

        if (featureCollection == null)
            throw new UnsupportedOperationException("Geobuf is not a feature collection");

        maxFeat = featureCollection.getFeaturesCount();

        // calculate what to divide coords by; coords are stored as fixed-point longs
        precisionDivisor = Math.pow(10, data.getPrecision());

        is.close();
    }

    @Override public boolean hasNext() {
        return i < maxFeat;
    }

    @Override public GeobufFeature next() {
        return new GeobufFeature(featureCollection.getFeatures(i++), keys, precisionDivisor);
    }
}
