package com.conveyal.osmlib;

import com.conveyal.osmlib.OSMEntity.Tag;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Used for making file-wide compression dictionaries
 */
public class TagCounter implements OSMEntitySink {

    TObjectIntHashMap<String> stringWeights = new TObjectIntHashMap<>();

    @Override
    public void writeBegin() throws IOException {
        // Do nothing.
    }

    @Override
    public void setReplicationTimestamp(long secondsSinceEpoch) {
        // Do nothing.
    }

    private void handleEntity (OSMEntity entity) {
        if (entity.hasNoTags()) {
            return;
        }
        for (Tag tag : entity.tags) {
            stringWeights.adjustOrPutValue(tag.key, tag.key.length(), tag.key.length());
            stringWeights.adjustOrPutValue(tag.value, tag.value.length(), tag.value.length());
            String kv = tag.toString();
            stringWeights.adjustOrPutValue(kv, kv.length(), kv.length());
        }
    }

    @Override
    public void writeNode(long id, Node node) throws IOException {
        handleEntity(node);
    }

    @Override
    public void writeWay(long id, Way way) throws IOException {
        handleEntity(way);
    }

    @Override
    public void writeRelation(long id, Relation relation) throws IOException {
        handleEntity(relation);
    }

    @Override
    public void writeEnd() throws IOException {
        // Output results.
        File outFile = new File("tagcount.csv");
        PrintStream os = new PrintStream(outFile);
        stringWeights.forEachEntry((s, n) -> {
            if (n > 100000) {
                os.printf("%d|%s\n", n, s);
            }
            return true; // continue iteration
        });
        os.close();
    }

}
