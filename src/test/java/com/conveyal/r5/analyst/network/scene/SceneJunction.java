package com.conveyal.r5.analyst.network.scene;

/// Instances are used to establish the topology of a street network. Two ways will be connected in
/// the resulting network if and only if they are connected to the same SceneJunction object with
/// the methods [SceneWay#from], [SceneWay#via], [SceneWay#to], or [Scene#join].
/// This is the only mechanism that will produce an OSM node that is shared between two ways, which
/// will be flagged as an intersection and can break ways containing it into multiple network edges.
/// Ways whose geometries touch or intersect but do not share a SceneJunction will not be connected
/// in the rendered output. Junctions must always be attached to more than one way, otherwise they will
/// be rejected by Scene validation.
public class SceneJunction {

    public final String name;

    /// Meters east of the scene origin.
    public final int x;

    /// Meters north of the scene origin.
    public final int y;

    SceneJunction (String name, int x, int y) {
        this.name = name;
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString () {
        return String.format("SceneJunction %s (%d, %d)", name, x, y);
    }

}
