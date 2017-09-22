package com.conveyal.r5.profile;

/**
 * Represents a travel mode used to traverse edges in the street graph.
 * Permissions on edges will allow or disallow traversal by these modes, and edges may be traversed at different
 * speeds depending on the selected mode.
 */
public enum StreetMode {
    WALK,
    BICYCLE,
    CAR
}
