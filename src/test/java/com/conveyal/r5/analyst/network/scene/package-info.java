/// Classes in this package are used to build small synthetic test networks ("scenes") with known
/// characteristics. A Scene is built up from objects we call "Scene Primitives" representing roads
/// and transit facilities. Fluent methods are used to lay them out in space using simple distances
/// in meters and cardinal directions. The topology of the network is established explicitly.
///
/// Although the resulting TransportNetwork is built using the standard production methods that
/// build networks from OSM and GTFS, the source OSM and GTFS need not be saved and loaded from
/// the interchange formats (PBF and ZIP) on disk. Instead, internal OSM and GTFS MapDB-backed
/// objects, here backed by memory instead of disk files, are generated from the Scene and passed
/// directly to the builder.
///
/// The Scene is buffered in memory as an object graph, with rendering to OSM, GTFS, and eventually
/// TransportNetwork deferred until after the entire Scene is constructed. This allows the Scene to
/// be validated before export and testing. Alternative renderings are possible. For example, the
/// Scene can also be rendered to an SVG diagram to help visualize or debug the network layout.
package com.conveyal.r5.analyst.network.scene;
