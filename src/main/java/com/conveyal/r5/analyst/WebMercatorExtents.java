package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;

import java.util.Objects;

/**
 * Really we should be embedding one of these in the tasks, grids, etc. to factor out all the common fields.
 * Equals and hashcode are semantic, for use as or within hashtable keys.
 * Created by abyrd on 2018-09-21
 */
public class WebMercatorExtents {

    public final int west;
    public final int north;
    public final int width;
    public final int height;
    public final int zoom;

    public WebMercatorExtents(int west, int north, int width, int height, int zoom) {
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.zoom = zoom;
    }

    public static WebMercatorExtents forTask (AnalysisTask task) {
        return new WebMercatorExtents(task.west, task.north, task.width, task.height, task.zoom);
    }

    public static WebMercatorExtents forGrid (PointSet pointSet) {
        if (pointSet instanceof Grid) {
            Grid grid = (Grid) pointSet;
            return new WebMercatorExtents(grid.west, grid.north, grid.width, grid.height, grid.zoom);
        } else {
            // Temporary way to bypass network preloading while freeform pointset functionality is being
            // developed. For now, the null return value is used in TravelTimeComputer to signal that the worker
            // should use a provided freeform pointset, rather than creating a WebMercatorGridPointSet based on the
            // parameters of the request.
            return null;
        }

    }

    public int getArea() {
        return width * height;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebMercatorExtents extents = (WebMercatorExtents) o;
        return west == extents.west &&
                north == extents.north &&
                width == extents.width &&
                height == extents.height &&
                zoom == extents.zoom;
    }

    @Override
    public int hashCode() {
        return Objects.hash(west, north, width, height, zoom);
    }

}
