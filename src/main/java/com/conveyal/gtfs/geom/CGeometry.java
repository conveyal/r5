package com.conveyal.gtfs.geom;

import java.io.Serializable;

/// The subclasses of CGeometry are custom implementations of geometry types, analogous to the
/// OpenGIS Simple Features implementation provided by JTS but optimized for memory use and most
/// importantly designed to work well with built-in and generic serialization systems.
///
/// The prefix C maintains a distinction from JTS geometries even where package names are not
/// visible. It stands for Conveyal and Compact.
///
/// These classes contain as few references as is reasonably possible, favoring packed arrays of
/// primitive types. The resulting object graphs should be tree-like and contain no shared
/// references to context objects like JTS factory or precision model objects. They are designed to
/// provide the coordinate reference system, precision model, and other characteristics we want
/// with no configuration or pluggable generic behavior.
///
/// These support only 2D coordinates which are assumed to be in WGS84 degrees. We are currently
/// using double-precision floats for simplicity but could conceivably use fixed-precision ints.
///
/// On this top-level CGeometry interface: Shared methods at this level always end up having very
/// generic return types that require later assignability checks like instanceof. The only reason
/// we might need a superinterface for all geometries is for some kind of serialization or storage
/// system generic across types.
public interface CGeometry extends Serializable {

}
