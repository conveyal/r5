package com.conveyal.r5.util;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nustaq.offheap.bytez.BasicBytez;
import org.nustaq.offheap.bytez.malloc.MallocBytez;
import org.nustaq.serialization.*;
import org.nustaq.serialization.coders.FSTBytezDecoder;
import org.nustaq.serialization.coders.FSTBytezEncoder;
import sun.nio.ch.FileChannelImpl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;

/**
 * A byte buffer in a memory mapped file for use by the FST serialization library.
 * https://github.com/RuedigerMoeller/fast-serialization
 *
 * Though Java allows memory-mapping a FileChannel to a ByteBuffer and FST has an (apparently experimental)
 * ByteBufferBasicBytez implementation, ByteBuffers are addressed with signed ints so can store only 2GB.
 * This introduces several levels of abstraction and truncates FST's internal long memory addresses.
 *
 * Here, we use the native methods on NIO FileChannel objects to allow 63-bit (signed long) addressing.
 * Based on FST's MMFBytez and info from http://nyeggen.com/post/2014-05-18-memory-mapping-%3E2gb-of-data-in-java/
 * Note that in practice on OSX you can't map >=4GB at once. Mapping 4GB - 4k does work though.
 * http://lists.apple.com/archives/darwin-kernel/2008/Mar/msg00049.html
 *
 * This Bytez implementation implements all the methods needed for FST to expand it automatically.
 * The alternative is to map a huge file and truncate it when done writing.
 */
public class ExpandingMMFBytez extends MallocBytez {

    private static final Method map0;
    private static final Method unmap0;
    private static final int MAP_READ_WRITE = 1;

    static {
        try {
            map0 = FileChannelImpl.class.getDeclaredMethod("map0", int.class, long.class, long.class);
            map0.setAccessible(true);
            unmap0 = FileChannelImpl.class.getDeclaredMethod("unmap0", long.class, long.class);
            unmap0.setAccessible(true);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static long mmap(FileChannel fileChannel, int imode, long start, long size) throws Exception {
        return (long) map0.invoke(fileChannel, imode, start, size);
    }

    private static void munmap(long address, long size) throws Exception {
        unmap0.invoke(null, address, size);
    }

    private RandomAccessFile randomAccessFile;
    private FileChannel fileChannel;

    public ExpandingMMFBytez(File file, long length, boolean clearFile) throws Exception {
        super(0, 0);
        if (file.exists() && clearFile) file.delete();
        if (file.exists()) length = file.length();
        randomAccessFile = new RandomAccessFile(file, "rw");
        randomAccessFile.setLength(length);
        this.fileChannel = randomAccessFile.getChannel();
        this.baseAdress = mmap(fileChannel, MAP_READ_WRITE, 0L, length);
        this.length = length;
    }

    @Override
    public void copyTo(BasicBytez other, long otherByteIndex, long myByteIndex, long lenBytes) {
        // Short circuit around unnecessary copies which will happen when growing a memory-mapped buffer, because
        // In memory-mapped buffers, rather than creating a new buffer we just unmap and remap the same file.
        if (other == this && otherByteIndex == myByteIndex) return;
        // This should fall back on MallocBytez copy, which will use potentially faster unsafe methods for copies to heap.
        // Copies to heap must be implemented for use during deserialization (e.g. reading class names).
        super.copyTo(other, otherByteIndex, myByteIndex, lenBytes);
    }

    /**
     * On an ExpandingMMFBytez, this method should only be called when FSTBytezEncoder is attempting to expand the
     * buffer. Therefore we just recycle the same ExpandingMMFBytez, but remap the file after making it bigger.
     * FSTBytezEncoder generally requests doubling the size of the buffer, which is problematic for large objects
     * as a 1GB file becomes 2GB then 4GB. Allocations of 2GB or 4GB may be refused for architectural reasons,
     * when slightly smaller allocations would be allowed. For this reason it would be desirable to grow in some
     * other more linear way, but that requires changes in the caller.
     * We can't simply ignore the size requested by the caller and increase by a constant amount, because writing
     * an object (e.g. a big numeric array) larger than the growth amount will then lead to buffer overruns.
     */
    @Override
    public BasicBytez newInstance(long size) {
        // Ignore the requested size from the caller and force linear size expansion.
        // This works until you try to perform a write bigger than MIN_SIZE_BYTES.
        // size = this.length + StreamCoderFactory.MIN_SIZE_BYTES;
        System.out.println("Resizing memory mapped buffer to " + size);
        try {
            munmap(baseAdress, length);
            randomAccessFile.setLength(size);
            baseAdress = mmap(fileChannel, MAP_READ_WRITE, 0L, size);
            length = size;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    /** This implementation hides the inappropriate method on the malloc-based superclass. */
    public void free() {
        try {
            munmap(baseAdress, length);
            fileChannel.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * FST grows the buffer by doubling its size.
     * After a few doublings, it may be much larger than the final amount of data that will be written into it.
     * By calling this method with the final amount of data written, the file can be shrunk down so it doesn't contain
     * a lot of empty space at the end.
     */
    public void truncateAndClose(long size) {
        try {
            munmap(baseAdress, length);
            fileChannel.truncate(size);
            System.out.println("Closing memory mapped file after truncating to length of " + size);
            fileChannel.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Encapsulates all the logic needed to serialize a single (potentially large) object into a file.
     * The file will double in size until it is big enough to hold the entire serialized object, then will be
     * truncated to the actual length of the written data.
     */
    public static void writeObjectToFile(File file, Object object) throws IOException {
        FSTConfiguration fstConfiguration = FSTConfiguration.createDefaultConfiguration();
        ExpandingMMFBytez.StreamCoderFactory coderFactory =
                new ExpandingMMFBytez.StreamCoderFactory(fstConfiguration, file);
        fstConfiguration.setStreamCoderFactory(coderFactory);
        FSTObjectOutput out = new FSTObjectOutput(fstConfiguration);
        out.writeObject(object, object.getClass());
        // Truncate the file to the actual amount of data written.
        coderFactory.truncateAndClose(out.getWritten());
        out.close();
    }

    /**
     * Encapsulates all the logic needed to deserialize a single object from a file.
     * The type of the resulting object is inferred from the caller.
     */
    public static <T> T readObjectFromFile (File file) throws Exception {
        FSTConfiguration fstConfiguration = FSTConfiguration.createDefaultConfiguration();
        ExpandingMMFBytez.StreamCoderFactory coderFactory =
                new ExpandingMMFBytez.StreamCoderFactory(fstConfiguration, file);
        fstConfiguration.setStreamCoderFactory(coderFactory);
        FSTObjectInput in = new FSTObjectInput(fstConfiguration);
        T result = (T) in.readObject(TransportNetwork.class);
        in.close();
        coderFactory.free();
        return result;
    }

    /**
     * An instance of this object is passed to an FSTConfiguration when creating an FSTObjectInput or FSTObjectOutput.
     * It provides the functionality of serializing/deserializing to/from a file.
     * It can handle huge objects up to about 4GB without eating much memory by using the memory mapped file mechanism.
     */
    public static class StreamCoderFactory implements FSTConfiguration.StreamCoderFactory {

        private static final long MIN_SIZE_BYTES = 1024 * 1024 * 100;
        private final FSTConfiguration conf;
        private final File file;
        private ExpandingMMFBytez bytez;
        static ThreadLocal input = new ThreadLocal();
        static ThreadLocal output = new ThreadLocal();

        public StreamCoderFactory(FSTConfiguration conf, File file) {
            this.conf = conf;
            this.file = file;
        }

        @Override
        public FSTEncoder createStreamEncoder() {
            try {
                bytez = new ExpandingMMFBytez(file, MIN_SIZE_BYTES, true);
                return new FSTBytezEncoder(conf, bytez);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public FSTDecoder createStreamDecoder() {
            try {
                // Size is negative to make this fail unless the file already exists.
                bytez = new ExpandingMMFBytez(file, -1, false);
                return new FSTBytezDecoder(conf, bytez);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public ThreadLocal getInput() {
            return input;
        }

        @Override
        public ThreadLocal getOutput() {
            return output;
        }

        /**
         * Call this function when you're done writing to the file's memory to shrink it to its final size,
         * e.g. memMapStreamCoderFactory.truncateFile(fstObjectOutput.getWritten());
         */
        public void truncateAndClose(int size) throws IOException {
            bytez.truncateAndClose(size);
        }

        public void free() {
            bytez.free();
        }

    }
}
