package com.conveyal.osmlib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.SynchronousQueue;

/**
 * A pipeline stage that receives uncompressed VEX blocks and writes them out in compressed form.
 * Receives blocks for compression synchronously through a zero-length blocking "queue".
 * This is meant to be run in a separate thread.
 */
/**
 * Accumulates data in a large in-memory buffer. When a write is about to cause the buffer to overflow, the contents
 * of the buffer are handed off to a secondary thread which writes the compressed data block to a downstream
 * OutputStream, preceded by the specified header bytes, the number of messages, and the number of compressed bytes.
 *
 * Java's ByteArrayOutputStream makes a copy when you fetch its backing byte array. Here, the output buffer and
 * compression process are integrated directly, avoiding this copy step.
 */
public class DeflatedBlockWriter extends OutputStream implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DeflatedBlockWriter.class);

    /**
     * The maximum expected size of an encoded but uncompressed entity.
     * A block will be considered finished when it is within this distance of the maximum block size.
     */
    public static final int MAX_MESSAGE_SIZE = 1024 * 64;

    /** A zero-length BlockingQueue that hands tasks to the compression/writing pipeline stage without buffering them. */
    private final SynchronousQueue<VEXBlock> synchronousQueue = new SynchronousQueue<>();

    private final OutputStream downstream;

    private int currentEntityType;

    private byte[] buffer;

    private int pos = 0;

    private int nEntitiesInBlock = 0;

    private final Thread blockWriterThread;

    /**
     * Create a DeflatedBlockWriter that writes deflated data to the given OutputStream.
     * Starts up a separate thread running the blockWriter's compression/writing loop.
     */
    public DeflatedBlockWriter(OutputStream downstream) {
        this.downstream = downstream;
        buffer = new byte[VEXBlock.BUFFER_SIZE];
        currentEntityType = VexFormat.VEX_NONE;
        blockWriterThread = new Thread(this);
        blockWriterThread.start();
    }

    /**
     * Hand off a block for writing. Handing off a block with element type NONE signals the end of output, and
     * will shut down the writer thread.
     */
    private void handOff(VEXBlock vexBlock) {
        try {
            synchronousQueue.put(vexBlock);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * This loop is run in a separate thread. It takes VEXBlocks one by one from the synchronous blocking queue,
     * deflates all the bytes that have been accumulated in each block and writes the deflated result to the
     * downstream OutputStream. The compression cannot be done incrementally or with a DeflaterOutputStream because
     * we need to write the compressed data length to the downstream OutputStream _before_ the compressed data.
     */
    @Override
    public void run() {

        while (true) {
            try {
                VEXBlock block = synchronousQueue.take(); // block until work is available
                if (block == VEXBlock.END_BLOCK) break;
                block.writeDeflated(downstream);
            } catch (InterruptedException ex) {
                // Preferably, we'd like to use a thread interrupt to tell the thread to shut down when there's no more
                // input. It should finish writing the last block before exiting.
                // InterruptedException should only happen during interruptable activity like sleeping or polling,
                // and we don't expect it to happen during I/O: http://stackoverflow.com/a/10962613/778449
                // However when writing to a PipedOutputStream, blocked write() calls can also notice the interrupt and
                // abort with an InterruptedIOException so this is not viable. Instead we use a special sentinel block.
                LOG.error("Block writer thread was interrupted while waiting for work.");
                break;
            }
        }
        // For predictability only one thread should write to a stream, and that thread should close the stream.
        // Or at least this is what piped streams impose.
        // See https://techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
        try {
            downstream.flush();
            downstream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void close() {
        try {
            // This will let the writing finish, then break out of the polling loop.
            this.handOff(VEXBlock.END_BLOCK);
            blockWriterThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Affects the header that will be prepended to subsequent blocks when they are written out. */
    public void setEntityType(int entityType) {
        currentEntityType = entityType;
    }

    /** Add a byte to the message fragment currently being constructed, flushing out a block as needed. */
    @Override
    public void write (int b) {
        // TODO catch out of range exceptions and warn that the message was too big.
        buffer[pos++] = (byte) b;
    }

    /**
     * Declare the message fragment under construction to be complete.
     * When there's not much space left in the buffer, this will end the block and start a new one.
     * @return whether a new block will be started after this message
     */
    public boolean endEntity() {
        nEntitiesInBlock += 1;
        if (pos > buffer.length - MAX_MESSAGE_SIZE) {
            endBlock();
            return true;
        }
        return false;
    }

    /**
     * Flush out any messages waiting in the buffer, ending the current block and starting a new one.
     * Note that this does _not_ include any waiting message fragment. You should call endMessage() first if you want
     * to include such a fragment.
     */
    public void endBlock() {
        if (nEntitiesInBlock > 0) {

            // Make a VEX block object to pass off to the compression/writer thread
            VEXBlock block = new VEXBlock();
            block.data = buffer;
            block.nBytes = pos;
            block.entityType = currentEntityType;
            block.nEntities = nEntitiesInBlock;

            // Give this block to the compression/writer thread synchronously (call blocks until thread is ready)
            handOff(block);

            // Create a new buffer and reset the position and message counters
            buffer = new byte[VEXBlock.BUFFER_SIZE];
            pos = 0;
            nEntitiesInBlock = 0;

        }
    }


}
