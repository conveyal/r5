package com.conveyal.r5.streets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents a turn restriction.
 */
public class TurnRestriction implements Serializable {
    public static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final Logger LOG = LoggerFactory.getLogger(TurnRestriction.class);

    private static final long serialVersionUID = -1;

    /** is this an only-turn restriction? */
    public boolean only;

    /** the edge at which this turn restriction starts */
    public int fromEdge;

    /** the edge we're turning onto */
    public int toEdge;

    /** the intermediate edges in this turn restriction */
    public int[] viaEdges = EMPTY_INT_ARRAY;

    /**
     * Reverses order of viaEdges this is used in reverse streetSearch for turn restrictions
     *
     * TODO: make this on the fly without copying arrays
     * @param viaEdges
     */
    public static void reverse(int[] viaEdges) {
        for (int i = 0; i < viaEdges.length / 2; i++) {
            int temp = viaEdges[i];
            viaEdges[i] = viaEdges[viaEdges.length - i - 1];
            viaEdges[viaEdges.length - i - 1] = temp;
        }
    }

    // via information is implied by the edges this turn restriction is attached to

    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (only) {
            sb.append("ONLY ");
        }
        sb.append("FROM:");
        sb.append(fromEdge);
        if (viaEdges != EMPTY_INT_ARRAY && viaEdges.length >= 1) {
            sb.append(" VIA:");
            sb.append(Arrays.toString(viaEdges));
        }
        sb.append(" TO:");
        sb.append(toEdge);

        return sb.toString();
    }

    /**
     * Remaps turn restriction with ONLY TURN to multiple turn restrictions with NO TURN
     *
     * If function is called on NO TURN restriction empty list is returned since no remapping is needed
     *
     * This is since only turn restrictions aren't supported in reverse street search.
     *
     * For example ONLY turn restriction from EN+1 to ENW over EW is remapped to:
     * NO TURN restrictions: EN+1 to EN, EN+1 to EE, EN+1 to ES, EN+1 to ENE and to
     * EN+1 to EW+1 over EW, EN+1 to ESW over EW
     *
     * @param streetLayer
     * @return list of new NO TURN restrictions which are semantically the same as one ONLY TURN restriction
     */
    List<TurnRestriction> remap(StreetLayer streetLayer) {
        List<TurnRestriction> retList = new ArrayList<>();

        if (only) {

            //Next edge in via edges or toEdge if current edge is last in viaEdges
            int[] next = new int[] {0};
            //R5 Index of current edge
            int currentEdgeIdx;

            //Loop over all via edges in turn restriction
            for(int posInTurnRestriction = -1; posInTurnRestriction < viaEdges.length; posInTurnRestriction++) {
                next[0] = posInTurnRestriction + 1 < viaEdges.length ?
                    viaEdges[posInTurnRestriction + 1] :
                    toEdge;
                if (posInTurnRestriction == -1) {
                    currentEdgeIdx = fromEdge;
                } else {
                    currentEdgeIdx = viaEdges[posInTurnRestriction];
                }
                LOG.debug("POI:{} FROM:{} next:{}", posInTurnRestriction, currentEdgeIdx, next);

                EdgeStore.Edge e = streetLayer.edgeStore.getCursor(currentEdgeIdx);
                int fromEdgeToVertex = e.getToVertex();
                int finalPosInTurnRestriction = posInTurnRestriction;
                //Goes over all outgoing CAR/BIKE traversable edges of toVertex of currentEdgeIdx an adds NO TURN turn restriction
                //Unless edge is next in current turn restriction
                streetLayer.outgoingEdges.get(fromEdgeToVertex).forEach(eidx -> {
                    if (eidx == next[0]) {
                        return true;
                    }
                    e.seek(eidx);
                    //filter car and bicycle traversible and add turn restriction from from and on this edges which aren't via/to
                    if (e.getFlag(EdgeStore.EdgeFlag.ALLOWS_CAR) || e.getFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE)) {
                        TurnRestriction restriction = new TurnRestriction();
                        restriction.fromEdge = fromEdge;
                        //Adds via edges which are via edges in original turn restriction up to this point
                        if (finalPosInTurnRestriction >= 0) {
                            restriction.viaEdges = Arrays.copyOfRange(viaEdges, 0, finalPosInTurnRestriction+1);
                        }
                        restriction.toEdge = eidx;
                        restriction.only = false;
                        LOG.debug("Made restriction:{}", restriction);
                        retList.add(restriction);
                    }
                    return true;

                });
            }
        }

        return retList;


    }


}
