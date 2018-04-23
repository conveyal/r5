package com.conveyal.r5.speed_test;

import com.conveyal.r5.speed_test.api.SpeedTestApplication;
import org.glassfish.grizzly.http.CompressionConfig;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.jersey.server.ContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SpeedTestHttpServer {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedTestHttpServer.class);

    private SpeedTest speedTest;

    private SpeedTestHttpServer(String[] args) throws Exception {
        this.speedTest = new SpeedTest(args);
    }

    public static void main(String[] args) {
        try {
            new SpeedTestHttpServer(args).start();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    public void start() throws Exception {
            createHttpServer(
                    new SpeedTestApplication(speedTest)
            ).start();

            waitForApplicationToShutdown();
    }

    private void waitForApplicationToShutdown() throws InterruptedException {
        Thread.currentThread().join();
    }

    private HttpServer createHttpServer(SpeedTestApplication application) {
        HttpServer httpServer = new HttpServer();
        httpServer.addListener(networkListener());

        httpServer.getServerConfiguration().addHttpHandler(
                ContainerFactory.createContainer(HttpHandler.class, application)
        );
        return httpServer;
    }

    private NetworkListener networkListener() {
        /* HTTP (non-encrypted) listener */
        NetworkListener httpListener = new NetworkListener("spped-test-main", "0.0.0.0", 8075);
        httpListener.setSecure(false);

        // For both HTTP and HTTPS listeners: enable gzip compression, set thread pool, add listener to httpServer.
        CompressionConfig cc = httpListener.getCompressionConfig();
        cc.setCompressionMode(CompressionConfig.CompressionMode.ON);
        cc.setCompressionMinSize(50000); // the min number of bytes to compress
        cc.setCompressableMimeTypes("application/json", "text/json"); // the mime types to compress

        httpListener.getTransport().setWorkerThreadPoolConfig(
                ThreadPoolConfig.defaultConfig().setCorePoolSize(2).setMaxPoolSize(8)
        );
        return httpListener;
    }
}
