package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.ProfileRequest;

import java.util.List;

/**
 * Represents a request to compute accessibility to destinations located on a regular Web Mercator grid. The grid is
 * loaded from a binary file on Amazon S3.
 */
public class GridRequest extends GenericClusterRequest {
    /** The profile request to use */
    public ProfileRequest request;

    public int zoom;
    public int west;
    public int north;
    public int width;
    public int height;

    /** x and y coords of this request within the grid specified above (i.e. 0, 0 is the top-left corner of the grid),
     * will override profile request */
    public int x;
    public int y;

    /** The grid key on S3 to compute access to */
    public String grid;

    /** Travel time cutoff (minutes) */
    public int cutoffMinutes;

    public final String type = "grid";

    /** Where should output of this job be saved? */
    public String outputQueue;
}
