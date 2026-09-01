package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.Geometry;

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
/// This interface declares a few general traits of geometry objects regarding bounding boxes,
/// JTS convertibility and validation (via that conversion). Not all geometry types are implemented
/// yet, focusing on use for zones in on-demand transit services.
public interface CGeometry extends Serializable {

    /// The bounding box of this geometry, which may have zero extent.
    CBox toBox ();

    /// Convert to a JTS geometry, for operations that use JTS algorithms such as point-in-polygon
    /// testing. Each call results in a new object so should be retained by the caller when reused.
    Geometry toJts ();

    /// For thorough validation, convert to JTS and throw the instance away. Validation of rings
    /// at construction time looks only at closed rings and the number of points. This method should
    /// also detect problems like zero-area polygons and self-intersecting rings.
    default boolean validate () {
        return toJts().isValid();
    }

}
