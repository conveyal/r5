package com.conveyal.r5.analyst.cluster.lambda;

import com.conveyal.r5.common.JsonUtilities;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteStreams;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPInputStream;

import static spark.Spark.port;
import static spark.Spark.post;

/**
 * A simple test hook to run the lambda worker locally with an HTTP interface.
 */
public class LocalLambdaWorker {
    public static void main (String... args) {
        port(1424);
        post("/", LocalLambdaWorker::isochrone);
    }

    private static Object isochrone(Request request, Response response) throws IOException, InterruptedException {
        LambdaWorker worker = new LambdaWorker();

        // this is a major hack but it's ok for development
        // Spark gzips the response automatically, but it's already gzipped, so ungzip it and pipe it into spark
        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream(pos);

        // http://stackoverflow.com/questions/1866255
        CountDownLatch latch = new CountDownLatch(1);


        new Thread(() -> {
            try {
                GZIPInputStream gzis = new GZIPInputStream(pis);
                ByteStreams.copy(gzis, response.raw().getOutputStream());
                latch.countDown();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();

        worker.handleRequest(request.raw().getInputStream(), pos, null);

        // wait for gunzip process to complete
        latch.await();

        return response.raw();
    }
}
