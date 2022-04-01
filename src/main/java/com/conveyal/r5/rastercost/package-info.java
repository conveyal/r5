/**
 * This package contains code for loading rasters and "draping" the street network over them, storing the profiles of
 * each edge raster field, and specifying cost functions that take those profiles as inputs (similar to path integrals
 * over a scalar field). This is used to account for elevation, tree cover, etc. in routing.
 *
 * For the time being, R5 does not consider cost independently of time. Any additional costs are represented in time
 * units (seconds) and cause the travel time to increase. This means that they cannot be used together with transit, as
 * walking through bright sun or pollution will cause the rider to arrive at a stop later and miss a vehicle. Handling
 * non-time costs and transit together implies multicriteria routing and is beyond the scope of this work. It is
 * technically possible but increases complexity and produces accessibility results that are much harder to interpret.
 *
 * Remaining problems: re-splitting edges more than once. Profiles for added edges (new roads).
 * One possible solution is to keep the raster available at all times and consult it for split or new edges at runtime.
 */
package com.conveyal.r5.rastercost;