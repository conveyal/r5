package com.conveyal.r5.kryo;

import com.conveyal.r5.transit.TransportNetwork;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ExternalizableSerializer;
import com.esotericsoftware.kryo.serializers.JavaSerializer;
import com.esotericsoftware.kryo.util.DefaultStreamFactory;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import gnu.trove.impl.hash.TPrimitiveHash;
import gnu.trove.list.array.TIntArrayList;
import org.mapdb.Fun;
import org.objenesis.strategy.SerializingInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.BitSet;

/**
 * This class groups the static methods for saving and loading TransportNetworks.
 * Created by abyrd on 2018-11-08
 */
public abstract class KryoNetworkSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(KryoNetworkSerializer.class);

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
            kryo = new Kryo(new InstanceCountingClassResolver(), new MapReferenceResolver(), new DefaultStreamFactory());
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
        // Kryo's default instantiation and deserialization of BitSets leaves them empty.
        // The Kryo BitSet serializer in magro/kryo-serializers naively writes out a dense stream of booleans.
        // BitSet's built-in Java serializer saves the internal bitfields, which is efficient. We use that one.
        kryo.register(BitSet.class, new JavaSerializer());
        // Instantiation strategy: how should Kryo make new instances of objects when they are deserialized?
        // The default strategy requires every class you serialize, even in your dependencies, to have a zero-arg
        // constructor (which can be private). The setInstantiatorStrategy method completely replaces that default
        // strategy. The nesting below specifies the Java approach as a fallback strategy to the default strategy.
        kryo.setInstantiatorStrategy(new Kryo.DefaultInstantiatorStrategy(new SerializingInstantiatorStrategy()));
        return kryo;
    }

    /**
     * Serialize the supplied network using Kryo, storing the result in a file.
     */
    public static void write (TransportNetwork network, File file) throws IOException {
        LOG.info("Writing transport network...");
        Output output = new Output(new FileOutputStream(file));
        Kryo kryo = makeKryo();
        kryo.writeClassAndObject(output, network);
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
        TransportNetwork result = (TransportNetwork) makeKryo().readClassAndObject(input);
        input.close();
        LOG.info("Done reading.");
        if (result.fareCalculator != null) {
            result.fareCalculator.transitLayer = result.transitLayer;
        }
        // The linkage cache is not serialized, so we need to put the pre-linked grid pointset back into the linkage
        // cache. This decreases the time to first result after a prebuilt network has been loaded to just a few seconds
        // even in the largest regions. However, currently only the linkage for walking is saved, so there will still
        // be a long pause at the first request for driving or cycling.
        // TODO just use a map for linkages? There should never be more than a handful per PointSet, one per mode.
        // The PointSets themselves are in a cache, although it does not currently have an eviction method.
        if (result.gridPointSet != null && result.linkedGridPointSet != null) {
            result.gridPointSet.linkageCache.put(
                new Fun.Tuple2<>(result.streetLayer, result.linkedGridPointSet.streetMode),
                result.linkedGridPointSet
            );
        }
        result.rebuildTransientIndexes();
        return result;
    }

}
