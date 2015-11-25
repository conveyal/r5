package com.conveyal.r5;

import com.conveyal.r5.streets.EdgeStore;
import org.nustaq.serialization.FSTConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Created by mabu on 25.11.2015.
 */
public interface IenumSet {
    Logger LOG = LoggerFactory.getLogger(IenumSet.class);



    default FSTConfiguration getConf() {
        FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();
        conf.registerSerializer(EnumSet.class, new MyEnumSetSerializer(), true);
        conf.registerSerializer(ArrayList.class, new MyEnumSetListSerializer(), false);
        return conf;
    }

    void serialize(OutputStream outputStream) throws IOException;

    default void serialize(ObjectOutputStream objectOutputStream) throws IOException {
        //objectOutputStream.close();

        LOG.info("SER:Writing int flags...");
        objectOutputStream.writeObject(this);
        objectOutputStream.close();
        LOG.info("SER:Done writing");

    }

    List<EnumSet<EdgeStore.EdgeFlag>> deserialize(InputStream inputStream) throws Exception;

    List<EnumSet<EdgeStore.EdgeFlag>> toList();
}
