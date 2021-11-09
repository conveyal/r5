package com.conveyal.gtfs;

import com.conveyal.analysis.util.JsonUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * Work on this paused because we need to somehow open and close the output JSON file using the same base name
 * as the MapDB file, and stream errors out to it in GTFSFeed#addError(com.conveyal.gtfs.error.GTFSError)
 * Created by abyrd on 2021-11-09
 */
public class ErrorRecorder {

    public void x () {
        try () {
            JsonUtil.objectMapper.writeValue(jsonWriter, feed.errors);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
