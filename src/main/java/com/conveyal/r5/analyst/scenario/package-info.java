/**
 * This package contains classes for modeling transport scenarios as an ordered series of modifications to be applied
 * to an underlying baseline graph. It is used for impact analysis: the interactive creation and comparison of the
 * accessibility effects of modifications to a transport network.
 *
 * It is important to note that each of these classes has a corresponding model for use in the UI and database.
 * Each type of modification has an R5 version (which is more stable over time) and a UI/DB version which can be
 * changed more freely. Conversion to the R5 types in this package is performed by an implementation of
 * com.conveyal.analysis.models.Modification.toR5().
 */
package com.conveyal.r5.analyst.scenario;