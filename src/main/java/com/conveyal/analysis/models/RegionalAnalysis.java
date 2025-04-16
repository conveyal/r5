package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.results.CsvResultType;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import org.locationtech.jts.geom.Geometry;

import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.file.FileCategory.RESULTS;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Represents a single regional (multi-origin) accessibility analysis,
 * which may have more than one percentile and cutoff.
 */
public class RegionalAnalysis extends Model implements Cloneable {
    public String regionId;
    public String bundleId;
    public String projectId;
    public String scenarioId;

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
     * Newer regional analyses (since release X in February 2020) can have more than one percentile.
     * If this is non-null it completely supersedes travelTimePercentile, which should be ignored.
     */
    public int[] travelTimePercentiles;

    public String[] destinationPointSetIds;

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

    public RegionalAnalysis clone () {
        try {
            return (RegionalAnalysis) super.clone();
        } catch (CloneNotSupportedException e) {
            throw AnalysisServerException.unknown(e);
        }
    }

    private String getMultiOriginFileBaseName(String prefix, String destinations, int percentile) {
        return String.format("%s_%s_P%d", prefix, destinations, percentile);
    }

    private String getMultiOriginFileBaseName(String destinations, int percentile) {
        return getMultiOriginFileBaseName(_id, destinations, percentile);
    }

    public FileStorageKey getMultiOriginAccessFileKey(String destinations, int percentile) {
        return new FileStorageKey(RESULTS, getMultiOriginFileBaseName(destinations, percentile) + ".access");
    }

    public FileStorageKey getSingleCutoffGridFileKey(String name, String destinations, int percentile, int cutoff, FileStorageFormat format) {
        String extension = format.extension.toLowerCase(Locale.ROOT);
        String path = String.format("%s_C%d.%s", getMultiOriginFileBaseName(name, destinations, percentile), cutoff, extension);
        return new FileStorageKey(RESULTS, path);
    }

    public FileStorageKey getSingleCutoffGridFileKey(String destinations, int percentile, int cutoff, FileStorageFormat format) {
        return getSingleCutoffGridFileKey(_id, destinations, percentile, cutoff, format);
    }

    public FileStorageKey getMultiOriginDualAccessFileKey(String destinations, int percentile) {
        return new FileStorageKey(RESULTS, getMultiOriginFileBaseName(destinations, percentile) + ".dual.access");
    }

    public FileStorageKey getSingleThresholdDualAccessGridFileKey(String name, String destinations, int percentile, int threshold, FileStorageFormat format) {
        String extension = format.extension.toLowerCase(Locale.ROOT);
        String path = String.format("%s_T%d.%s", getMultiOriginFileBaseName(name, destinations, percentile), threshold, extension);
        return new FileStorageKey(RESULTS, path);
    }

    public FileStorageKey getSingleThresholdDualAccessGridFileKey(String destinations, int percentile, int threshold, FileStorageFormat format) {
        return getSingleThresholdDualAccessGridFileKey(_id, destinations, percentile, threshold, format);
    }

    public FileStorageKey getCsvResultFileKey(CsvResultType resultType) {
        return new FileStorageKey(RESULTS, _id + "_" + resultType + ".csv");
    }

    public FileStorageKey getScenarioJsonFileKey(String scenarioId) {
        return new FileStorageKey(BUNDLES, _id + "_" + scenarioId + ".json");
    }
}
