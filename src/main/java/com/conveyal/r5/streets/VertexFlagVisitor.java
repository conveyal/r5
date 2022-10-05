package com.conveyal.r5.streets;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

/**
 * Saves flagSearchQuantity number of vertices which have wantedFlag
 * and are at least minTravelTimeSeconds away from the origin
 * <p>
 * If vertex is found multiple times vertex with lower quantityToMinimize is saved
 */
class VertexFlagVisitor implements RoutingVisitor {
    private final int minTravelTimeSeconds;

    private final RoutingVariable dominanceVariable;

    private final int maxVertices;

    private final VertexStore.VertexFlag wantedFlag;

    VertexStore.Vertex v;

    TIntObjectMap<RoutingState> vertices = new TIntObjectHashMap<>();

    //Save vertices which are too close so that if they appear again (with longer path to them)
    // they are also skipped
    TIntSet skippedVertices = new TIntHashSet();

    public VertexFlagVisitor(StreetLayer streetLayer, RoutingVariable dominanceVariable,
                             VertexStore.VertexFlag wantedFlag, int maxVertices, int minTravelTimeSeconds) {
        this.minTravelTimeSeconds = minTravelTimeSeconds;
        this.dominanceVariable = dominanceVariable;
        this.wantedFlag = wantedFlag;
        this.maxVertices = maxVertices;
        v = streetLayer.vertexStore.getCursor();
    }

    /**
     * If vertex at current state has wantedFlag it is added to vertices map
     * if it is more then minTravelTimeSeconds away and has backState and non negative vertexIdx
     * <p>
     * If vertex is found multiple times vertex with lower quantityToMinimize is saved
     *
     * @param state
     */
    @Override
    public void visitVertex(RoutingState state) {
        if (state.vertex < 0 ||
                //skips origin states for bikeShare (since in cycle search for bikeShare origin states
                //can be added to vertices otherwise since they could be traveled for minTravelTimeSeconds with different transport mode)
                state.backState == null || state.durationFromOriginSeconds < minTravelTimeSeconds ||
                skippedVertices.contains(state.vertex)
        ) {
            // Make sure that vertex to which you can come sooner then minTravelTimeSeconds won't be used
            // if a path which uses more then minTravelTimeSeconds is found
            // since this means we need to walk/cycle/drive longer then required
            if (state.vertex > 0 && state.durationFromOriginSeconds < minTravelTimeSeconds) {
                skippedVertices.add(state.vertex);
            }
            return;
        }
        v.seek(state.vertex);
        if (v.getFlag(wantedFlag)) {
            if (!vertices.containsKey(state.vertex)
                    || vertices.get(state.vertex).getRoutingVariable(dominanceVariable) > state
                    .getRoutingVariable(dominanceVariable)) {
                vertices.put(state.vertex, state);
            }
        }

    }

    /**
     * @return true when flagSearchQuantity vertices are found
     */
    public boolean shouldBreakSearch() {
        return vertices.size() >= this.maxVertices;
    }

    /**
     * @return found vertices with wantedFlag. Same format of returned value as in {@link StreetRouter#getReachedVertices(VertexStore.VertexFlag)}
     */
    public TIntObjectMap<RoutingState> getVertices() {
        return vertices;
    }
}
