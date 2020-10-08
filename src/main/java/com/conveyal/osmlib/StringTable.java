package com.conveyal.osmlib;

import com.google.protobuf.ByteString;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.openstreetmap.osmosis.osmbinary.Osmformat;

import java.util.ArrayList;
import java.util.List;

/**
 * Deduplicates strings and assigns them one-based integer codes, for PBF format.
 */
public class StringTable {

    private TObjectIntMap<String> codeForString;

    private List<String> stringForCode;

    public StringTable () {
        codeForString = new TObjectIntHashMap<>(500, 0.6f, -1);
        stringForCode = new ArrayList<>(500);
        stringForCode.add(""); // zero is a special value, indicating the end of a list of KV pairs for a node.
    }

    public int getCode(String string) {
        int code = codeForString.get(string);
        if (code == -1) {
            code = stringForCode.size(); // do not use codeForString.size(), we need one-based codes.
            stringForCode.add(string);
            codeForString.put(string, code);
        }
        return code;
    }

    public void clear() {
        stringForCode.clear();
        codeForString.clear();
        stringForCode.add(""); // zero is a special value, indicating the end of a list of KV pairs for a single node.
    }

    public Osmformat.StringTable.Builder toBuilder () {
        Osmformat.StringTable.Builder builder = Osmformat.StringTable.newBuilder();
        for (String s : stringForCode) {
            builder.addS(ByteString.copyFromUtf8(s));
        }
        return builder;
    }

}
