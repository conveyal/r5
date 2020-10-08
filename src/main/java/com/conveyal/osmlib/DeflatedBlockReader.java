package com.conveyal.osmlib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.SynchronousQueue;

/**
 * A pipeline stage that reads in deflated VEX blocks.
 * Hands off the results of decompression synchronously through a zero-length blocking "queue".
 * Constructing a DeflatedBlockReader starts up a separate thread that reads full compressed data blocks one at a time
 * into a memory buffer. Whenever a block is taken away from the reader, it will start parallel read-ahead and
 * decompression of the next block.
 */
public class DeflatedBlockReader implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DeflatedBlockReader.class);

    private final SynchronousQueue<VEXBlock> synchronousQueue = new SynchronousQueue<>();

    private final InputStream upstream;

    private final Thread thread;

    /**
     * Construct a new DeflatedBlockReader, which then runs itself in a parallel thread.
     * @param upstream the InputStream it will read from
     */
    public DeflatedBlockReader(InputStream upstream) {
        this.upstream = upstream;
        thread = new Thread(this);
        thread.start();
    }

    /**
     * Wait for one block to become available, then return it.
     * @return the next block or the special END_BLOCK reference if there are no more blocks.
     */
    public VEXBlock nextBlock() {
        try {
            VEXBlock block = synchronousQueue.take();
            return block;
        } catch (InterruptedException e) {
            LOG.error("Interrupted while waiting for a block to become available. This shouldn't happen.");
            return VEXBlock.END_BLOCK;
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                VEXBlock block = new VEXBlock();
                block.readDeflated(upstream);
                if (block.entityType == VexFormat.VEX_NONE) {
                    // There are no more blocks, end of file.
                    synchronousQueue.put(VEXBlock.END_BLOCK);
                    break;
                } else {
                    synchronousQueue.put(block);
                }
            }
            upstream.close();
        } catch (InterruptedException e) {
            LOG.error("Interrupted while trying to hand off a block. This shouldn't happen.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
