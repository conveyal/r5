/**
 * This contains fare calculation implementations that are used in accessibility analysis to apply cost limits while
 * a search is happening. This is a resource limiting problem and requires pareto-path routing. It's a distinct
 * problem from calculating fares for Modeify or normal point to point routing, where fares are not considered during
 * routing and are only calculated after the path is already found.
 */
package com.conveyal.r5.analyst.fare;
