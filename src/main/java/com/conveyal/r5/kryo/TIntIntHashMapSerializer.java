package com.conveyal.r5.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import gnu.trove.impl.Constants;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntIntHashMap;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Kryo Serializer for Trove primitive int-int hash maps.
 * Based on the corresponding Externalizable implementation from Trove itself.
 *
 * Using optimizePositive on keys and values, serialized NL R5 networks with linkages are 13% smaller.
 * Writing with this serializer is much faster than using the Kryo Externalizable serializer.
 */
public class TIntIntHashMapSerializer extends Serializer<TIntIntHashMap> {

    private final boolean optimizePositive = true;

    private final boolean varInts = true;

    /**
     * Based on writeExternal in the hierarchy of TIntIntHashMap.
     */
    @Override
    public void write(Kryo kryo, Output output, TIntIntHashMap map) {

        // Do not write load and compaction factors.
        // They're not public and we don't ever modify them in a principled way.

        // No-entry key and value. Often zero or -1 so don't optimize for positive values, do optimize for small values.
        output.writeVarInt(map.getNoEntryKey(), false);
        output.writeVarInt(map.getNoEntryValue(), false);

        // Number of entries, always zero or positive.
        output.writeVarInt(map.size(), true);

        // All entries, most are positive in our application?
        if (varInts) {
            map.forEachEntry((k, v) -> {
                output.writeVarInt(k, optimizePositive);
                output.writeVarInt(v, optimizePositive);
                return true;
            });
        } else {
            map.forEachEntry((k, v) -> {
                output.writeInt(k, optimizePositive);
                output.writeInt(v, optimizePositive);
                return true;
            });
        }
    }

    @Override
    public TIntIntHashMap read(Kryo kryo, Input input, Class type) {

        // No-entry key and value. Often zero or -1 so don't optimize for positive values, do optimize for small values.
        int noEntryKey = input.readVarInt(false);
        int noEntryVal = input.readVarInt(false);

        // Number of entries, always zero or positive.
        int size = input.readVarInt(true);

        TIntIntHashMap map = new TIntIntHashMap(size, Constants.DEFAULT_LOAD_FACTOR, noEntryKey, noEntryVal);

        if (varInts) {
            for (int i = 0; i < size; i++) {
                int key = input.readVarInt(optimizePositive);
                int val = input.readVarInt(optimizePositive);
                map.put(key, val);
            }
        } else {
            for (int i = 0; i < size; i++) {
                int key = input.readInt(optimizePositive);
                int val = input.readInt(optimizePositive);
                map.put(key, val);
            }
        }
        return map;
    }
}
