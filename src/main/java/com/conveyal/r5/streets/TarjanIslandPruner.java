package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import gnu.trove.stack.TIntStack;
import gnu.trove.stack.array.TIntArrayStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Prune islands from a graph using Tarjan's strong-components algorithm, described in
 * Tarjan, R. “Depth-First Search and Linear Graph Algorithms.” SIAM Journal on Computing 1, no. 2 (1972): 146–60. doi:10.1137/0201010.
 * and summarized at
 * https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm
 *
 * We prune islands by mode; what is an island for pedestrians is probably not an island for cars.
 *
 * Note that the order in which you remove islands for different modes is important. Island removal for cycling should be
 * done after island removal for walking, because we allow walking bikes. Consider the following situation, where *
 * represents a vertex or small island, -B- represents a bikeable edge, and -W- represents a walkable edge:
 *
 *  * -B- * -W- * -B- * -W- * -B- *
 *
 * There is no island here for bikes, because they can simply be walked down the walking street. However, there are walking
 * islands here, and the walk permissions on edges will be removed because they are part of islands. At that point there will
 * be biking islands. Thus the walk island removal must be run before the bike island removal.
 *
 * This way we are not simply removing islands that are in weak components, but removing islands that are in strong
 * components (a weak component is one where for every pair of vertices i and j, there is a path i -> j or a path
 * j -> i but not necessarily both, whereas a strong component has both). This is most relevant for cars; there are
 * few one-way pedestrian streets (and I don't believe r5 even supports them), and it is legal to walk a bike the wrong
 * way down a one way street. One-way cul-de-sacs are clearly a data error but do exist, for instance
 * http://www.openstreetmap.org/way/207971576 which is a ramp into an unmapped parking garage in Arlington, VA, USA.
 *
 * One concern with this island remover is that it does not consider turn restrictions, so it theoretically would leave
 * a situation like this in the graph:
 *
 *  B - C
 *  |
 *  A
 *
 * Suppose all edges can be traversed by cars in both directions, but there is a no-right-turn restriction from AB to BC.
 * C is not part of a larger strong component because it cannot be reached due to the turn restriction. However, this
 * case is believed to be sufficiently rare not to worry about.
 *
 * The algorithm used here is a modified version of the one Tarjan described; Tarjan's algorithm used recursion, but with
 * real world graphs a naïve implementation using recursion quickly caused a StackOverflow in the JVM. The modified
 * algorithm works as follows.
 *
 * Tarjan's algorithm uses a depth-first search. We loop over all vertices, and when we find one that hasn't been explored, we
 * add it to the toExplore stack.
 *
 * We then pop a vertex off the toExplore stack. If it has not previously been explored, we
 * give it a consecutive discovery index, set the lowest-discovery-index vertex that is both reachable from this vertex and a
 * is also a predecessor vertex to the itself, and we add it to the tarjan stack to indicate it is a precessor of any
 * vertices explored later in the search. We push this vertex back on the toExplore stack to make sure it is updated once all successor
 * nodes have been explored, and then loop over all the edges usable by the chosen mode originating from the selected vertex.
 *
 * For each edge, we check if the target vertex has previously been explored. If it has not, we put the source vertex
 * and then the target vertex back on the toExplore stack. This way, the target vertex will
 * be popped from the toExplore stack and explored, and then the source vertex will be popped and updated. If it has,
 * we check if it is on the tarjan stack; if it has, we know that it is a predecessor to the source vertex, and update
 * the lowest-discovery-index predecessor based on the discovery index of the target vertex.
 *
 * If the vertex popped off the toExplore stack has already been explored, we first check all successors; if they have a higher discovery
 * index than the vertex popped off the stack, we know they were explored downstream of the source vertex, and their
 * lowest-discovery-index predecessor was on the Tarjan stack when they were explored. If the lowest-discovery-index predecessor
 * is less than the lowest-discovery-index predecessor of the popped vertex, we update the lowest-discovery-index predecessor of the
 * popped vertex. This looping over edges is potentially less efficient than the original implementation which used the call
 * stack to keep track of which successors could have been updated, but it is simple, eliminates recursion, and is quick
 * even in relatively large street networks.
 *
 * If the vertex popped off the toExplore stack has itself as the lowest-discovery-index predecessor, we have found a
 * strong component. We pop vertices off the Tarjan stack until we reach the vertex popped from the toExplore stack.
 * These vertices form a strong component, which we record.
 *
 * Once all strong components have been found, we loop through all of them and if they have a size less than the minimum
 * component size, we remove permissions for the relevant mode from all edges connected to their vertices.
 *
 * We previously used a flood-fill algorithm designed for undirected graphs. This worked okay for walking and biking because
 * the graph is effectively undirected; for every edge there is a corresponding back edge (we don't support one-way streets
 * for walking, and walking a bike the wrong way down a one-way street is always an option). However, for cars this is an
 * issue; there can be components due to bad data that are one-way cul-de-sacs (or small islands of streets connected to the
 * outside world only by a one-way street). Obviously this is bad data or we'd have a lot of cars piled up in one place,
 * but in some sense needing to do island removal at all is a fix for bad data; islands should not exist in OSM (except
 * for physical islands that must be reached by boat, etc).
 * (cf. Sachar, Louis. Wayside School Gets a Little Stranger. New York, HarperCollins. 1995, in which one-way elevators are installed).
 *
 * @author mattwigway
 */
public class TarjanIslandPruner {
    private static final Logger LOG = LoggerFactory.getLogger(TarjanIslandPruner.class);

    private final StreetLayer streets;
    public final int minComponentSize;
    public final StreetMode mode;

    /**
     * The stack used in Tarjan's algorithm. Simply called stack in the paper and Wikipedia, but renamed here to
     * differentiate from the toExplore stack
     */
    private TIntStack tarjanStack;

    /** Quick lookups of whether something is on the stack */
    private BitSet onTarjanStack;

    /** The stack of which vertices to explore, replaces the recursion in Tarjan's algorithm */
    private TIntStack toExplore;

    /**
     * The lowest discovery index of any predecessor vertex known to be reachable from this vertex, indexed by r5 vertex index
     * This is called LOWLINK in Tarjan 1972 and the Wikipedia article.
     */
    private int[] lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor;

    /**
     * The order vertices were discovered in, indexed by r5 vertex index, called index in the Wikipedia article and
     * NUMBER in Tarjan 1972
     */
    private int[] discoveryIndex;

    /** keep track of the order of vertex discovery */
    private int nextDiscoveryIndex;

    /** The strong components identified by the algorithm */
    private List<TIntSet> strongComponents = new ArrayList<>();

    /** Reüse the edge cursor to save memory */
    private final EdgeStore.Edge edgeCursor;

    public TarjanIslandPruner(StreetLayer streetLayer, int minComponentSize, StreetMode mode) {
        this.streets = streetLayer;
        this.minComponentSize = minComponentSize;
        this.mode = mode;

        tarjanStack = new TIntArrayStack();
        onTarjanStack = new BitSet();
        toExplore = new TIntArrayStack();
        lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor = new int[streets.getVertexCount()];
        Arrays.fill(lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor, -1);
        discoveryIndex = new int[streets.getVertexCount()];
        Arrays.fill(discoveryIndex, -1);
        edgeCursor = streetLayer.edgeStore.getCursor();
    }

    public void run () {
        LOG.info("Removing islands for mode {}", mode);
        long startTime = System.currentTimeMillis();

        for (int sourceVertex = 0; sourceVertex < streets.getVertexCount(); sourceVertex++) {
            if (discoveryIndex[sourceVertex] == -1) {
                toExplore.push(sourceVertex);

                while (toExplore.size() > 0) {
                    int vertex = toExplore.pop();

                    if (discoveryIndex[vertex] != -1) {
                        if (!onTarjanStack.get(vertex)) continue; // this strong component has already been found and removed from the tarjanStack

                        // we have previously visited this vertex and are looping back to it,
                        // update lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor based on successors

                        // keep track of whether we've visited every successor yet
                        // NB this still works if successors were found previous to reaching this root node, because all
                        // edges are always looped over.
                        // Successors that have not been explored are queued to be
                        // explored, and those that have been explored are used to tighten
                        // lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor without further iteration.
                        boolean[] everySuccessorExplored = new boolean[] { true };

                        // only update lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor based on those vertices
                        // that were explored from this vertex, not ones that had been explored earlier, so that we're
                        // sure that the lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor only represents
                        // vertices that are predecessors.
                        // Looping here has somewhat higher complexity than the original algorithm but uses less memory than
                        // keeping a tarjanStack of which successor was most recently explored, and still runs in <10 seconds on
                        // a relatively large (metropolitan Washington, DC) graph.
                        forEachOutgoingEdge(vertex, e -> {
                            int toVertex = e.getToVertex();
                            if (discoveryIndex[toVertex] == -1) everySuccessorExplored[0] = false;
                            if (discoveryIndex[toVertex] > discoveryIndex[vertex] &&
                                    lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex] > lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[toVertex])
                                // only update if toVertex was discovered later than the vertex under consideration
                                // i.e. if it was explored downstream of this vertex.
                                // We don't want to accidentally set lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor to something that is not a predecessor of this vertex
                                lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex] = lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[toVertex];
                        });

                        // this is the root of a strong component if every successor has been explored and the lowest reachable
                        // vertex is this vertex
                        if (everySuccessorExplored[0] && discoveryIndex[vertex] == lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex]) {
                            // we're back at the root of a strong component, pop it off the tarjanStack
                            TIntSet strongComponent = new TIntHashSet();
                            int poppedVertex;
                            do {
                                // since we put this vertex on the tarjanStack at the start of iteration, we know the loop will
                                // terminate, no need to check for no data value.
                                poppedVertex = tarjanStack.pop();

                                onTarjanStack.clear(poppedVertex);
                                strongComponent.add(poppedVertex);
                            } while (poppedVertex != vertex);

                            strongComponents.add(strongComponent);
                        }
                    } else {
                        // start recursion. This is basically what the strongconnect() function in the wikipedia pseudocode
                        // example starts with
                        discoveryIndex[vertex] = lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex] = nextDiscoveryIndex++;
                        tarjanStack.push(vertex);
                        onTarjanStack.set(vertex);

                        // if this vertex is a dead end or if all successors have been explored,
                        // it will not be hit again unless we put it on the deque here.
                        // this ensures it will be hit one more time after the loop over successors
                        // if it's already been popped at that point, no harm done.
                        toExplore.push(vertex);

                        forEachOutgoingEdge(vertex, e -> {
                            int toVertex = e.getToVertex();
                            if (discoveryIndex[toVertex] == -1) {
                                // re-mark this vertex to update lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor
                                // after the vertex has been explored
                                toExplore.push(vertex);
                                // Unlike in the original algorithm, we are not recursing but instead marking this vertex
                                // to be explored in the next iteration of the loop. This means that all edges that lead
                                // to vertices already explored (else clause below) will be used to update lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor
                                // before any unexplored vertices are explored. This is harmless as it basically is just
                                // a reordering of edge exploration.
                                toExplore.push(toVertex);
                            } else if (onTarjanStack.get(toVertex)) {
                                // don't recurse, just mark that the next vertex is lowest reachable
                                // note that we are using the discovery index, not the lowest reachable discovery index from the
                                // to vertex, because we don't know if the lowest reachable discovery index is a predecessor
                                // of this vertex
                                if (lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex] > discoveryIndex[toVertex]) lowestDiscoveryIndexOfReachableVertexKnownToBePredecessor[vertex] = discoveryIndex[toVertex];
                            }
                        });
                    }
                }
            }
        }

        LOG.info("Found {} strong components for mode {} using Tarjan's algorithm in {}sec",
                strongComponents.size(), mode, (System.currentTimeMillis() - startTime) / 1000d);

        startTime = System.currentTimeMillis();

        int nComponentsRemoved = 0;
        int nVerticesRemoved = 0;

        for (TIntSet strongComponent : strongComponents) {
            if (strongComponent.size() < minComponentSize) {
                nComponentsRemoved++;
                for (TIntIterator it = strongComponent.iterator(); it.hasNext();) {
                    int vertex = it.next();
                    nVerticesRemoved++;
                    removePermissionsAroundVertex(vertex);
                }
            }
        }

        LOG.info("Removed {} strong component (islands) with fewer than {} vertices for mode {} in {}sec. {} vertices removed.",
                nComponentsRemoved, minComponentSize, mode, (System.currentTimeMillis() - startTime) / 1000d, nVerticesRemoved);
    }

    /** Loop over every outgoing edge for a particular mode */
    public void forEachOutgoingEdge (int vertex, Consumer<EdgeStore.Edge> consumer) {
        streets.outgoingEdges.get(vertex).forEach(eidx -> {
            edgeCursor.seek(eidx);

            // filter by mode
            switch (mode) {
                case WALK:
                    if (!edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)) return true;
                    else break;
                case CAR:
                    if (!edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_CAR)) return true;
                    else break;
                case BICYCLE:
                    // include ped mode here, because walking bikes is a thing you can do.
                    boolean allowsBike = edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE) ||
                                    edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
                    if (!allowsBike) return true;
                    else break;
                default:
                    throw new IllegalArgumentException(String.format("Unsupported mode %s for island removal", mode));
            }

            consumer.accept(edgeCursor);
            return true; // continue iteration over outgoing edges
        });
    }

    /** Remove the permissions around a vertex for the desired mode. Returns the number of edges affected */
    public void removePermissionsAroundVertex (int vertex) {
        for (TIntList edgeList : new TIntList[] { streets.outgoingEdges.get(vertex), streets.incomingEdges.get(vertex) }) {
            edgeList.forEach(eidx -> {
                edgeCursor.seek(eidx);
                switch (mode) {
                    case CAR:
                        edgeCursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
                        break;
                    case BICYCLE:
                        edgeCursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
                        break;
                    case WALK:
                        edgeCursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
                        break;
                    default:
                        throw new IllegalArgumentException(String.format("Unsupported mode %s for island removal", mode));
                }
                return true; // continue iteration
            });
        }
    }
}
