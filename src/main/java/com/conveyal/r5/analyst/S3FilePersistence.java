package com.conveyal.r5.analyst;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.TransferProgress;
import com.amazonaws.services.s3.transfer.Upload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

import static com.conveyal.r5.common.Util.human;

/**
 * An implementation of long-term file persistence using Amazon AWS S3.
 *
 * For any file whose length is not known in advance, the S3 client will buffer the whole thing in memory.
 * This obviously dangerous because it can exhaust memory. However, when we are going to gzip files, we just don't
 * know the size in advance. If the files are known to be reasonably small, we can do all the layout and compression
 * in memory buffers then upload from the buffer, whose length is known.
 *
 * From docs: "callers must supply the size of options in the stream through the content length field in the
 * ObjectMetadata parameter. If no content length is specified for the input stream, then TransferManager will attempt
 * to buffer all the stream contents in memory and upload the options as a traditional, single part upload.
 * Because the entire stream contents must be buffered in memory, this can be very expensive, and should be
 * avoided whenever possible."
 *
 */
public class S3FilePersistence extends FilePersistence {

    public static final Logger LOG = LoggerFactory.getLogger(S3FilePersistence.class);

    /**
     * The common prefix of all accessed buckets.
     * Example: if baseBucket is "analysis-staging", files of type POLYGON will be in bucket "analysis-staging-polygons".
     * NOTE: For now this only applies to reading files using S3FilePersistence, not writing them.
     * TODO if this works well, extend to all other bucket names and file loading / saving.
     */
    private final String baseBucket;

    /** Manage transfers to S3 in the background, so we can continue calculating while uploading. */
    private final TransferManager transferManager;

    // Low-level client, for now we're trying the high-level TransferManager TODO maybe use this so we don't have to shut down the transferManager
    private final AmazonS3 amazonS3;

    public S3FilePersistence (String region, String baseBucket) {

        this.amazonS3 = AmazonS3ClientBuilder.standard()
                // .enableAccelerateMode() // this fails looking up s3-accelerate.amazonaws.com
                .withRegion(region)
                .build();

        this.transferManager = TransferManagerBuilder.standard()
                .withS3Client(amazonS3)
                .build();

        this.baseBucket = baseBucket;
    }

    @Override
    public void saveData(String directory, String fileName, PersistenceBuffer persistenceBuffer) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            // Set content encoding to gzip. This way browsers will decompress on download using native deflate code.
            // http://www.rightbrainnetworks.com/blog/serving-compressed-gzipped-static-files-from-amazon-s3-or-cloudfront/
            metadata.setContentEncoding("gzip");
            metadata.setContentType(persistenceBuffer.getMimeType());
            // We must setContentLength or the S3 client will re-buffer the InputStream into another memory buffer.
            metadata.setContentLength(persistenceBuffer.getSize());
//            amazonS3.putObject(directory, fileName, persistenceBuffer.getInputStream(), metadata);
            final Upload upload = transferManager.upload(directory, fileName, persistenceBuffer.getInputStream(), metadata);
            upload.addProgressListener(new UploadProgressLogger(upload));
            // Block until upload completes to avoid accumulating unlimited uploads in memory.
            upload.waitForCompletion();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream getData(FileCategory category, String name) {
        String fullBucketName = getFullBucketName(category);
        LOG.info("Fetching {} with name {} from S3 bucket {}.", category, name, fullBucketName);
        S3Object s3Object = amazonS3.getObject(fullBucketName, name);
        // TODO progress reporting, cacheing in local file using getObject(GetObjectRequest getObjectRequest, File destinationFile) or TransferManager.download()
        InputStream inputStream = s3Object.getObjectContent();
        return inputStream;
    }

    /**
     * Example: for baseBucket "analysis-staging" and file type POLYGON, returns "analysis-staging-polygons".
     */
    private String getFullBucketName (FileCategory category) {
        return baseBucket + "-" + category.name().toLowerCase() + "s";
    }

    @Override
    public void shutdown() {
        transferManager.shutdownNow();
    }

    // TODO wire this up to our r5/analysis progress and task system
    private static class UploadProgressLogger implements ProgressListener {

        private static final int LOG_INTERVAL_SECONDS = 5;

        private final long beginTime;

        private long lastLogTime;

        private Upload upload;

        public UploadProgressLogger(Upload upload) {
            this.upload = upload;
            beginTime = System.currentTimeMillis();
            lastLogTime = beginTime;
        }

        @Override
        public void progressChanged(ProgressEvent progressEvent) {
            final ProgressEventType eventType = progressEvent.getEventType();
            if (eventType == ProgressEventType.REQUEST_BYTE_TRANSFER_EVENT ||
                eventType == ProgressEventType.TRANSFER_COMPLETED_EVENT) {
                long now = System.currentTimeMillis();
                if (now > lastLogTime + LOG_INTERVAL_SECONDS * 1000 ||
                    eventType == ProgressEventType.TRANSFER_COMPLETED_EVENT) {
                    TransferProgress transferProgress = upload.getProgress();
                    double durationSec = (now - beginTime) / 1000D;
                    LOG.info("{} transferred {} of {} ({} percent), duration {}, speed {})",
                            upload.getDescription(),
                            human(transferProgress.getBytesTransferred(), "B"),
                            human(transferProgress.getTotalBytesToTransfer(), "B"),
                            transferProgress.getPercentTransferred(),
                            human(durationSec, "s"),
                            human(transferProgress.getBytesTransferred() / durationSec, "B/sec"));
                    lastLogTime = now;
                }
            } else if (eventType == ProgressEventType.TRANSFER_FAILED_EVENT) {
                LOG.error("{}: TRANSFER FAILED.", upload.getDescription());
            }
        }
    }
}

/* Some old code that might be relevant if we need to set regions.
   The client creation process can detect the region that EC2 instances are running in.
   When testing locally setting the region is essential.

        // Clients for communicating with Amazon web services
        // When creating the S3 and SQS clients use the default credentials chain.
        // This will check environment variables and ~/.aws/credentials first, then fall back on
        // the auto-assigned IAM role if this code is running on an EC2 instance.
        // http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-roles.html
        AmazonS3ClientBuilder builder = AmazonS3Client.builder();
        builder.setRegion(ec2info.region);
        AmazonS3 s3 = builder.build();
*/
