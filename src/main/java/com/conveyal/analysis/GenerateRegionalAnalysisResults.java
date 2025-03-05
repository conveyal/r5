package com.conveyal.analysis;

import com.conveyal.analysis.controllers.RegionalAnalysisController;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.TaskAction;
import com.conveyal.r5.util.ExceptionUtils;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import org.mongojack.DBCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class GenerateRegionalAnalysisResults implements TaskAction {
    private final FileStorage fileStorage;
    private final FileStorageFormat[] validFormats = new FileStorageFormat[]{
            FileStorageFormat.GRID,
            FileStorageFormat.GEOTIFF,
            FileStorageFormat.PNG
    };
    private static final Logger LOG = LoggerFactory.getLogger(GenerateRegionalAnalysisResults.class);
    public GenerateRegionalAnalysisResults (FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public void action(ProgressListener progressListener) throws Exception {
        DBObject query = QueryBuilder.start().or(
                QueryBuilder.start("travelTimePercentiles").is(null).get(),
                QueryBuilder.start("cutoffsMinutes").is(null).get(),
                QueryBuilder.start("destinationPointSetIds").is(null).get()
        ).get();
        int filesGenerated = 0;
        try (DBCursor<RegionalAnalysis> cursor = Persistence.regionalAnalyses.find(query)) {
            LOG.info("Query found {} regional analyses to process.", cursor.count());
            while (cursor.hasNext()) {
                RegionalAnalysis regionalAnalysis = cursor.next();
                LOG.info("Processing regional analysis {} of {}.", regionalAnalysis._id, regionalAnalysis.accessGroup);
                int[] percentiles = Objects.requireNonNullElseGet(regionalAnalysis.travelTimePercentiles, () -> new int[]{regionalAnalysis.travelTimePercentile});
                int[] cutoffs = Objects.requireNonNullElseGet(regionalAnalysis.cutoffsMinutes, () -> new int[]{regionalAnalysis.cutoffMinutes});
                String[] destinationPointSetIds = Objects.requireNonNullElseGet(regionalAnalysis.destinationPointSetIds, () -> new String[]{regionalAnalysis.grid});

                // Iterate through all values and generate all possible formats for them.
                for (String destinationPointSetId : destinationPointSetIds) {
                    OpportunityDataset destinations = Persistence.opportunityDatasets.get(destinationPointSetId);
                    for (int cutoffMinutes : cutoffs) {
                        for (int percentile : percentiles) {
                            for (FileStorageFormat format : validFormats) {
                                RegionalAnalysisController.getSingleCutoffGrid(
                                        fileStorage,
                                        regionalAnalysis,
                                        destinations,
                                        cutoffMinutes,
                                        percentile,
                                        format
                                );
                                filesGenerated++;
                            }
                        }
                    }
                }

                // Save as modern types
                regionalAnalysis.cutoffsMinutes = cutoffs;
                regionalAnalysis.travelTimePercentiles = percentiles;
                regionalAnalysis.destinationPointSetIds = destinationPointSetIds;
                Persistence.regionalAnalyses.put(regionalAnalysis);

                LOG.info("Finished processing {} of {}.", regionalAnalysis._id, regionalAnalysis.accessGroup);
            }
        } catch (Exception e) {
            LOG.error(ExceptionUtils.shortAndLongString(e));
        }
        LOG.info("Method `getSingleCutoffGrid` was run {} times.", filesGenerated);
    }
}
