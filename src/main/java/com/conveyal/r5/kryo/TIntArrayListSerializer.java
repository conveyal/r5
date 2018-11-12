package com.conveyal.r5.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import gnu.trove.list.array.TIntArrayList;

/**
 * Kryo Serializer for Trove primitive int array lists.
 * Based on the corresponding Externalizable implementation from Trove itself.
 * Created by abyrd on 2018-08-29
 *
 * Using varInts, serialized r5 networks are 10% smaller. optimizePositive doesn't make a size difference.
 * Writing with this serializer is much faster than using the Kryo Externalizable serializer.
 */
public class TIntArrayListSerializer extends Serializer<TIntArrayList> {

    private final boolean optimizePositive = false;

    private final boolean varInts = true;

    @Override
    public void write(Kryo kryo, Output output, TIntArrayList list) {

        // no-entry value
        output.writeVarInt(list.getNoEntryValue(), false);

        // list length
        output.writeVarInt(list.size(), true);

        // list entries
        for (int i = 0; i < list.size(); i++) {
            if (varInts) {
                output.writeVarInt(list.get(i), optimizePositive);
            } else {
                output.writeInt(list.get(i), optimizePositive);
            }
        }

    }

    @Override
    public TIntArrayList read(Kryo kryo, Input input, Class type) {
        int noEntryValue = input.readVarInt(false);
        int length = input.readVarInt(true);
        TIntArrayList list = new TIntArrayList(length, noEntryValue);
        for (int i = 0; i < length; i++) {
            if (varInts) {
                list.add(input.readVarInt(optimizePositive));
            } else {
                list.add(input.readInt(optimizePositive));
            }
        }
        return list;
    }
}
