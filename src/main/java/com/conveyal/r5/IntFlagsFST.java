package com.conveyal.r5;

import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Created by mabu on 25.11.2015.
 */
public class IntFlagsFST implements IenumSet, Serializable {

    TIntList flags;

    private static final Logger LOG = LoggerFactory.getLogger(IntFlagsFST.class);

    public enum LEdgeFlag {
        UNUSED(0),
        BIKE_PATH(1),
        SIDEWALK(2),
        CROSSING(3),
        ROUNDABOUT(4),
        ELEVATOR(5),
        STAIRS(6),
        PLATFORM(7),
        BOGUS_NAME(8),
        NO_THRU_TRAFFIC(9),
        SLOPE_OVERRIDE(10),
        TRANSIT_LINK(11), // This edge is a one-way connection from a street to a transit stop. Target is a transit stop index, not an intersection index.

        // Permissions
        ALLOWS_PEDESTRIAN(16),
        ALLOWS_BIKE(17),
        ALLOWS_CAR(18),
        ALLOWS_WHEELCHAIR(19),

        // Bicycle level of traffic stress: http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
        // comments below pasted from document.

        /**
         * Presenting little traffic stress and demanding little attention from cyclists, and attractive enough for a
         * relaxing bike ride. Suitable for almost all cyclists, including children trained to safely cross intersections.
         * On links, cyclists are either physically separated from traffic, or are in an exclusive bicycling zone next to
         * a slow traffic stream with no more than one lane per direction, or are on a shared road where they interact
         * with only occasional motor vehicles (as opposed to a stream of traffic) with a low speed differential. Where
         * cyclists ride alongside a parking lane, they have ample operating space outside the zone into which car
         * doors are opened. Intersections are easy to approach and cross.
         */
        BIKE_LTS_1(20),

        /**
         * Presenting little traffic stress and therefore suitable to most adult cyclists but demanding more attention
         * than might be expected from children. On links, cyclists are either physically separated from traffic, or are
         * in an exclusive bicycling zone next to a well-confined traffic stream with adequate clearance from a parking
         * lane, or are on a shared road where they interact with only occasional motor vehicles (as opposed to a
         * stream of traffic) with a low speed differential. Where a bike lane lies between a through lane and a rightturn
         * lane, it is configured to give cyclists unambiguous priority where cars cross the bike lane and to keep
         * car speed in the right-turn lane comparable to bicycling speeds. Crossings are not difficult for most adults.
         */
        BIKE_LTS_2(21),

        /**
         * More traffic stress than LTS 2, yet markedly less than the stress of integrating with multilane traffic, and
         * therefore welcome to many people currently riding bikes in American cities. Offering cyclists either an
         * exclusive riding zone (lane) next to moderate-speed traffic or shared lanes on streets that are not multilane
         * and have moderately low speed. Crossings may be longer or across higher-speed roads than allowed by
         * LTS 2, but are still considered acceptably safe to most adult pedestrians.
         */
        BIKE_LTS_3(22),

        /**
         * A level of stress beyond LTS3. (this is in fact the official definition. -Ed.)
         */
        BIKE_LTS_4(23); // also known as FLORIDA_AVENUE

        public final int flag;
        private LEdgeFlag (int bitNumber) {
            flag = 1 << bitNumber;
        }

    }

    public IntFlagsFST(List<EnumSet<EdgeStore.EdgeFlag>> flags) {
        this.flags = new TIntArrayList(flags.size());
        for (EnumSet<EdgeStore.EdgeFlag> flagSet : flags) {
            int iflag = 0;
            for (EdgeStore.EdgeFlag flag: flagSet) {
                LEdgeFlag lflag = LEdgeFlag.valueOf(flag.toString());
                iflag = iflag | lflag.flag;
            }
            this.flags.add(iflag);
        }
    }

    @Override
    public void serialize(OutputStream outputStream) throws IOException {
        LOG.info("Writing int flags...");
        FSTObjectOutput out = getConf().getObjectOutput(outputStream);
        out.writeObject(this, IntFlagsFST.class);
        out.flush();
        outputStream.close();
        LOG.info("Done writing.");

    }

    @Override
    public List<EnumSet<EdgeStore.EdgeFlag>> deserialize(InputStream inputStream) throws Exception {
        LOG.info("Reading int flags...");
        FSTObjectInput in = getConf().getObjectInput(inputStream);
        IntFlagsFST intFlagsFST = (IntFlagsFST) in.readObject(IntFlagsFST.class);
        LOG.info("Finished");

        return intFlagsFST.toList();
    }

    @Override
    public List<EnumSet<EdgeStore.EdgeFlag>> toList() {
        List<EnumSet<EdgeStore.EdgeFlag>> listFlags = new ArrayList<>(flags.size());
        flags.forEach(flag -> {
            EnumSet<EdgeStore.EdgeFlag> flags = EnumSet.noneOf(EdgeStore.EdgeFlag.class);
            for (LEdgeFlag edgeFlag: LEdgeFlag.values()) {
                if ((flag & edgeFlag.flag) != 0) {
                    flags.add(EdgeStore.EdgeFlag.valueOf(edgeFlag.toString()));
                }
            }
            listFlags.add(flags);
            return true;
        });
        return listFlags;
    }


}
