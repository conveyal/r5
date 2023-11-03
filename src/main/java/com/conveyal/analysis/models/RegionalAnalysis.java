package com.conveyal.analysis.models;

import com.conveyal.analysis.results.AccessCsvResultWriter;
import com.conveyal.analysis.results.CsvResultType;
import com.conveyal.analysis.results.GridResultWriter;
import com.conveyal.analysis.results.PathCsvResultWriter;
import com.conveyal.analysis.results.RegionalResultWriter;
import com.conveyal.analysis.results.TemporalDensityCsvResultWriter;
import com.conveyal.analysis.results.TimeCsvResultWriter;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * Represents a single regional (multi-origin) accessibility analysis,
 * which may have more than one percentile and cutoff.
 */
public class RegionalAnalysis extends Model implements Cloneable {
    public String regionId;
    public String bundleId;
    public String projectId;
    public String scenarioId;

    public int variant;

    public String workerVersion;

    public int zoom;
    public int width;
    public int height;
    public int north;
    public int west;

    /**
     * TODO: Fix confusing naming. An `AnalysisRequest` is used to create a `AnalysisWorkerTask` (which `RegionalTask`
     *       extends) but is not the same as a task. Naming this parameter `request` can create confusion that it refers
     *       to that incoming `AnalysisRequest` object and not the `RegionalTask` itself. Either 1) rename this parameter
     *       "task" (which would require a migration) or 2) rename `AnalysisRequest` something more unique to
     *       differentiate or 3) ?.
     */
    public RegionalTask request;

    /**
     * Single percentile of travel time being used in this analysis. Older analyses could have only one percentile.
     * If the analysis is pre-percentiles and is using Andrew Owen-style accessibility, value is -1.
     * If the analysis has more than one percentile, value is -2.
     */
    @Deprecated
    public int travelTimePercentile = -1;

    /**
     * Newer regional analyses (since release X in February 2020) can have more than one percentile.
     * If this is non-null it completely supersedes travelTimePercentile, which should be ignored.
     */
    public int[] travelTimePercentiles;

    /** Single destination pointset id (for older analyses that did not allow multiple sets of destinations). */
    @Deprecated
    public String grid;

    public String[] destinationPointSetIds;

    /**
     * Older analyses (up to about January 2020, before release X) had only one cutoff.
     * New analyses with more than one cutoff will have this set to -2.
     */
    @Deprecated
    public int cutoffMinutes;

    /**
     * The different travel time thresholds used in this analysis to include or exclude opportunities from an
     * accessibility metric. Supersedes the singular cutoffMinutes used in older analyses. For non-step decay functions
     * these cutoffs should correspond to the point where the monotonically decreasing decay function first reaches 0.5.
     */
    public int[] cutoffsMinutes;

    /**
     * A geometry defining the bounds of this regional analysis.
     * For now, we will use the bounding box of this geometry, but eventually we should figure out which
     * points should be included and only include those points. When you have an irregular analysis region,
     * See also: https://commons.wikimedia.org/wiki/File:The_Gerry-Mander_Edit.png
     */
    public Geometry bounds;

    /** Is this Analysis complete? */
    public boolean complete;

    /** Has this analysis been (soft) deleted? */
    public boolean deleted;

    /**
     * Storage locations of supplemental regional analysis results intended for export (rather than direct UI display).
     * A map from result type (times, paths, or access) to the file name. This could conceivably be a more structured
     * Java type rather than a String-keyed map, but for now we want to maintain flexibility for new result types.
     *
     * These CSV regional results are meant by design to be downloaded as files by end users. We store their filenames
     * to facilitate download via the UI without having to replicate any backend logic that generates filenames from
     * analysis characteristics.
     *
     * This stands in opposition to our original regional analysis results: grids of accessibility for different travel
     * times and percentiles. These are fetched by the backend and returned to the UI on demand. Those file names are
     * derived from the ID of the regional analysis and other details. The backend has all that naming logic in one
     * place because these files are not meant for direct download by the end user, so the UI doesn't need to replicate
     * any of that logic.
     */
    public Map<CsvResultType, String> resultStorage = new HashMap<>();

    /**
     * Create results writers for this regional analysis and a task. Stores the result paths that are created by the
     * writers.
     */
    public List<RegionalResultWriter> createResultWriters(RegionalTask task) {
        // Create the result writers. Store their result file paths in the database.
        var resultWriters = new ArrayList<RegionalResultWriter>();
        if (!task.makeTauiSite) {
            if (task.recordAccessibility) {
                if (task.originPointSet != null) {
                    // Freeform origins - create CSV regional analysis results
                    var accessWriter = new AccessCsvResultWriter(task);
                    resultWriters.add(accessWriter);
                    resultStorage.put(accessWriter.resultType(), accessWriter.getFileName());
                } else {
                    // Gridded origins - create gridded regional analysis results
                    resultWriters.addAll(GridResultWriter.createWritersFromTask(this, task));
                }
            }

            if (task.recordTimes) {
                var timesWriter = new TimeCsvResultWriter(task);
                resultWriters.add(timesWriter);
                resultStorage.put(timesWriter.resultType(), timesWriter.getFileName());
            }

            if (task.includePathResults) {
                var pathsWriter = new PathCsvResultWriter(task);
                resultWriters.add(pathsWriter);
                resultStorage.put(pathsWriter.resultType(), pathsWriter.getFileName());
            }

            if (task.includeTemporalDensity) {
                if (task.originPointSet == null) {
                    // Gridded origins. The full temporal density information is probably too voluminous to be useful.
                    // We might want to record a grid of dual accessibility values, but this will require some serious
                    // refactoring of the GridResultWriter.
                    // if (job.templateTask.dualAccessibilityThreshold > 0) { ... }
                    throw new RuntimeException("Temporal density of opportunities cannot be recorded for gridded origin points.");
                } else {
                    var tDensityWriter = new TemporalDensityCsvResultWriter(task);
                    resultWriters.add(tDensityWriter);
                    resultStorage.put(tDensityWriter.resultType(), tDensityWriter.getFileName());
                }
            }
            checkArgument(notNullOrEmpty(resultWriters), "A regional analysis should always create at least one grid or CSV file.");
        }
        return resultWriters;
    }

    public RegionalAnalysis clone () {
        try {
            return (RegionalAnalysis) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
