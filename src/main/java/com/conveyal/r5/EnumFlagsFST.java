package com.conveyal.r5;

import com.conveyal.r5.streets.EdgeStore;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;

/**
 * Created by mabu on 25.11.2015.
 */
public class EnumFlagsFST implements IenumSet, Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(EnumFlagsFST.class);

    List<EnumSet<EdgeStore.EdgeFlag>> flags;

    public EnumFlagsFST(List<EnumSet<EdgeStore.EdgeFlag>> flags) {
        this.flags = flags;
    }

    @Override
    public void serialize(OutputStream outputStream) throws IOException {
        LOG.info("Writing enum flags...");
        FSTObjectOutput out = getConf().getObjectOutput(outputStream);
        out.writeObject(this, EnumFlagsFST.class);
        out.flush();
        outputStream.close();
        LOG.info("Done writing.");
    }

    @Override
    public List<EnumSet<EdgeStore.EdgeFlag>> deserialize(InputStream inputStream) throws Exception {
        LOG.info("Reading enum flags...");
        FSTObjectInput in = getConf().getObjectInput(inputStream);
        EnumFlagsFST intFlagsFST = (EnumFlagsFST) in.readObject(EnumFlagsFST.class);
        LOG.info("Finished");

        return intFlagsFST.toList();
    }

    @Override
    public List<EnumSet<EdgeStore.EdgeFlag>> toList() {
        return flags;
    }
}
