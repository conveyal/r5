package com.conveyal.analysis;

import com.conveyal.analysis.controllers.RegionalAnalysisController;
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
import org.mongojack.DBProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        DBObject query = QueryBuilder.start().and(
                QueryBuilder.start("deleted").is(false).get(),
                QueryBuilder.start().or(
                        QueryBuilder.start("travelTimePercentiles").is(null).get(),
                        QueryBuilder.start("cutoffsMinutes").is(null).get(),
                        QueryBuilder.start("destinationPointSetIds").is(null).get()
                ).get()
        ).get();
        int filesGenerated = 0;
        Set<String> regionalAnalysesWithErrors = new HashSet<>();
        try (DBCursor<RegionalAnalysis> cursor = Persistence.regionalAnalyses.find(query, DBProjection.exclude("request.scenario.modifications"))) {
            List<RegionalAnalysis> analyses = cursor.toArray();
            LOG.info("Query found {} regional analyses to process.", analyses.size());
            for (RegionalAnalysis regionalAnalysis : analyses) {
                LOG.info("Processing regional analysis {} of {}.", regionalAnalysis._id, regionalAnalysis.accessGroup);
                int[] percentiles = Objects.requireNonNullElseGet(regionalAnalysis.travelTimePercentiles, () -> new int[]{regionalAnalysis.travelTimePercentile});
                int[] cutoffs = Objects.requireNonNullElseGet(regionalAnalysis.cutoffsMinutes, () -> new int[]{regionalAnalysis.cutoffMinutes});
                String[] destinationPointSetIds = Objects.requireNonNullElseGet(regionalAnalysis.destinationPointSetIds, () -> new String[]{regionalAnalysis.grid});

                // Iterate through all values and generate all possible formats for them.
                for (String destinationPointSetId : destinationPointSetIds) {
                    for (int cutoffMinutes : cutoffs) {
                        for (int percentile : percentiles) {
                            for (FileStorageFormat format : validFormats) {
                                try {
                                    RegionalAnalysisController.getSingleCutoffGrid(
                                            fileStorage,
                                            regionalAnalysis,
                                            destinationPointSetId,
                                            cutoffMinutes,
                                            percentile,
                                            format
                                    );
                                    filesGenerated++;
                                } catch (Exception e) {
                                    LOG.error("Error generating single cutoff grid for {}", regionalAnalysis._id);
                                    LOG.error(ExceptionUtils.shortAndLongString(e));
                                    regionalAnalysesWithErrors.add(regionalAnalysis._id);
                                }
                            }
                        }
                    }
                }

                LOG.info("Finished processing regional analysis {} of {}.", regionalAnalysis._id, regionalAnalysis.accessGroup);
            }
        } catch (Exception e) {
            LOG.error(ExceptionUtils.shortAndLongString(e));
        }
        LOG.info("Method `getSingleCutoffGrid` was run {} times.", filesGenerated);
        LOG.info("Errors found in {}", String.join(", ", regionalAnalysesWithErrors));
    }
}
