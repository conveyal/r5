package com.conveyal.gtfs.geom;

import java.io.Serializable;

/// This may add no benefit over the package name.
/// Shared methods at this level always end up having super generic return types that require
/// later assignability checks like instanceof. The only reason we might need a superinterface
/// for all geometries is for some kind of serialization or storage system generic across types.
public interface CGeometry extends Serializable {

}
