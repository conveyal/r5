package com.conveyal.r5.kryo;

import com.conveyal.r5.SoftwareVersion;
import com.conveyal.kryo.InstanceCountingClassResolver;
import com.conveyal.kryo.TIntArrayListSerializer;
import com.conveyal.kryo.TIntIntHashMapSerializer;
import com.conveyal.r5.transit.TransportNetwork;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ExternalizableSerializer;
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers;
import com.esotericsoftware.kryo.serializers.JavaSerializer;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import gnu.trove.impl.hash.TPrimitiveHash;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntIntHashMap;
import org.objenesis.strategy.SerializingInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

/**
 * This class groups the static methods for saving and loading TransportNetworks.
 *
 * Each serialization or deserialization operation creates a completely new Kryo instance, so there should be no
 * issues with thread safety, as long as the object being serialized is not being changed simultaneously.
 *
 * Created by abyrd on 2018-11-08
 */
public abstract class KryoNetworkSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(KryoNetworkSerializer.class);

    /**
     * This string should be changed to a new value (nv2, nv3...) each time the network storage format changes.
     * It should also be changed when the semantic content changes from that produced by earlier versions, even when
     * the serialization format itself does not change. This will ensure newer workers will not load cached older files.
     * We considered using an ISO date string as the version but that could get confusing when seen in filenames.
     *
     * History of Network Version (NV) changes (in production releases):
     * nv3 since v7.0: switched to Kryo 5 serialization, WebMercatorGridPointSet now contains nested WebMercatorExtents
     * nv2 since 2022-04-05
     * nv1 since 2021-04-30: stopped rebuilding networks for every new r5 version, manually setting this version string
     *
     * When prototyping new features, use a unique identifier such as the branch or a commit ID, not sequential nvX ones.
     * This avoids conflicts when multiple changes are combined in a single production release, or some are abandoned.
     */
    public static final String NETWORK_FORMAT_VERSION = "nv3";

    public static final byte[] HEADER = "R5NETWORK".getBytes();

    /** Set this to true to count instances and print a report including which serializer is handling each class. */
    private static final boolean COUNT_CLASS_INSTANCES = false;

    /**
     * Factory method ensuring that we configure Kryo exactly the same way when saving and loading networks, without
     * duplicating code. We could explicitly register all classes in this method, which would avoid writing out the
     * class names the first time they are encountered and guarantee that the desired serialization approach was used.
     * Because these networks are so big though, pre-registration should provide very little savings.
     * Registration is more important for small network messages.
     */
    private static Kryo makeKryo () {
        Kryo kryo;
        if (COUNT_CLASS_INSTANCES) {
            kryo = new Kryo(new InstanceCountingClassResolver(), null);
        } else {
            kryo = new Kryo();
        }
        // Auto-associate classes with default serializers the first time each class is encountered.
        kryo.setRegistrationRequired(false);
        // Handle references and loops in the object graph, do not repeatedly serialize the same instance.
        kryo.setReferences(true);
        // Hash maps generally cannot be properly serialized just by serializing their fields.
        // Kryo's default serializers and instantiation strategies don't seem to deal well with Trove primitive maps.
        // Certain Trove class hierarchies are Externalizable though, and define their own optimized serialization
        // methods. addDefaultSerializer will create a serializer instance for any subclass of the specified class.
        // The TPrimitiveHash hierarchy includes all the trove primitive-primitive and primitive-Object implementations.
        kryo.addDefaultSerializer(TPrimitiveHash.class, ExternalizableSerializer.class);
        // We've got a custom serializer for primitive int array lists, because there are a lot of them and the custom
        // implementation is much faster than deferring to their Externalizable implementation.
        kryo.register(TIntArrayList.class, new TIntArrayListSerializer());
        // Likewise for TIntIntHashMaps - there are lots of them in the distance tables.
        kryo.register(TIntIntHashMap.class, new TIntIntHashMapSerializer());
        // Kryo's default instantiation and deserialization of BitSets leaves them empty.
        // The Kryo BitSet serializer in magro/kryo-serializers naively writes out a dense stream of booleans.
        // BitSet's built-in Java serializer saves the internal bitfields, which is efficient. We use that one.
        kryo.register(BitSet.class, new JavaSerializer());
        // Instantiation strategy: how should Kryo make new instances of objects when they are deserialized?
        // The default strategy requires every class you serialize, even in your dependencies, to have a zero-arg
        // constructor (which can be private). The setInstantiatorStrategy method completely replaces that default
        // strategy. The nesting below specifies the Java approach as a fallback strategy to the default strategy.
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new SerializingInstantiatorStrategy()));
        return kryo;
    }

    /**
     * Serialize the supplied network using Kryo, storing the result in a file.
     */
    public static void write (TransportNetwork network, File file) throws IOException {
        LOG.info("Writing transport network...");
        Output output = new Output(new FileOutputStream(file));
        Kryo kryo = makeKryo();
        output.write(HEADER);
        kryo.writeObject(output, NETWORK_FORMAT_VERSION);
        kryo.writeObject(output, SoftwareVersion.instance.commit);
        kryo.writeObject(output, network);
        output.close();
        LOG.info("Done writing.");
        if (COUNT_CLASS_INSTANCES) {
            ((InstanceCountingClassResolver)kryo.getClassResolver()).summarize();
        }
    }

    /**
     * Read the given file and decode with Kryo into a new R5 TransportNetwork object.
     * Transient fields that were not serialized will be rebuilt (indexes and cached PointSet linkage).
     */
    public static TransportNetwork read (File file) throws Exception {
        LOG.info("Reading transport network...");
        Input input = new Input(new FileInputStream(file));
        Kryo kryo = makeKryo();
        byte[] header = new byte[HEADER.length];
        input.read(header, 0, header.length);
        if (!Arrays.equals(HEADER, header)) {
            throw new RuntimeException("Unrecognized file header. Is this an R5 Kryo network?");
        }
        String formatVersion = kryo.readObject(input, String.class);
        String commit = kryo.readObject(input, String.class);
        LOG.info("Loading network from file format version {}, written by R5 commit {}", formatVersion, commit);
        if (!NETWORK_FORMAT_VERSION.equals(formatVersion)) {
            throw new RuntimeException(
                String.format("File format version is %s, this R5 requires %s", formatVersion, NETWORK_FORMAT_VERSION)
            );
        }
        TransportNetwork result = kryo.readObject(input, TransportNetwork.class);
        input.close();
        LOG.info("Done reading.");
        if (result.fareCalculator != null) {
            result.fareCalculator.transitLayer = result.transitLayer;
        }
        result.rebuildTransientIndexes();
        return result;
    }

}
