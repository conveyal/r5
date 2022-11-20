package com.conveyal.analysis.results;

import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import java.util.Date;

/**
 * Mark the regional analysis as completed when the job ends.
 */
public class DbResultWriter implements RegionalResultWriter {
    private final AnalysisDB db;
    private final String regionalAnalysisId;

    public DbResultWriter(AnalysisDB db, String regionalAnalysisId) {
        this.db = db;
        this.regionalAnalysisId = regionalAnalysisId;
    }

    @Override
    public void writeOneWorkResult(RegionalWorkResult workResult) {
        // TODO increment?
    }

    @Override
    public void terminate() {
        // TODO mark as failed in the database?
    }

    @Override
    public void finish() throws Exception {
        // Write updated regionalAnalysis object back out to database.
        db.regionalAnalyses.collection.updateOne(
                Filters.eq("_id", regionalAnalysisId),
                Updates.combine(
                        Updates.set("complete", true),
                        Updates.set("updatedAt", new Date())
                )
        );
    }
}
