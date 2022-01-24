/**
 * This package contains code for loading rasters and "draping" the street network over them, storing the profiles of
 * each edge raster field, and specifying cost functions that take those profiles as inputs (similar to path integrals
 * over a scalar field). This is used to account for elevation, tree cover, etc. in routing.
 *
 * Remaining problems: re-splitting edges more than once. Profiles for added edges (new roads).
 * One possible solution is to keep the raster available at all times and consult it for split or new edges at runtime.
 */
package com.conveyal.r5.rastercost;