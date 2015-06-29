package org.opentripplanner.analyst.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.opentripplanner.profile.ProfileRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Enqueues a bunch of messages to simulate an Analyst Cluster job
 *
 * $ aws s3 ls analyst-demo-graphs
 * $ aws sqs list-queues
 */
public class JobSimulator {

    public static String USERID = "userA";

    public static void main(String[] args) {

        DefaultHttpClient httpClient = new DefaultHttpClient();

        String s3prefix = args[0];
        String pointSetId = args[1];
        String graphId = args[2];
        int nOrigins = Integer.parseInt(args[3]);

        String jobId = compactUUID();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        List<AnalystClusterRequest> requests = new ArrayList<>();

        IntStream.range(0, nOrigins).forEach(i -> {
            // Enqueue one fake origin
            ProfileRequest profileRequest = new ProfileRequest();
            AnalystClusterRequest clusterRequest = new AnalystClusterRequest(pointSetId, graphId, profileRequest);
            clusterRequest.userId = USERID;
            clusterRequest.jobId = jobId;
            clusterRequest.id = Integer.toString(i);
            clusterRequest.outputLocation = s3prefix + "_output";
            clusterRequest.destinationPointsetId = pointSetId;
            requests.add(clusterRequest);
        });

//        try {
//            objectMapper.writeValue(System.out, requests);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }

        String url = String.format("http://localhost:9001/jobs");
        HttpPost httpPost = new HttpPost(url);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            objectMapper.writeValue(out, requests);
            // System.out.println(out.toString());
            httpPost.setEntity(new ByteArrayEntity(out.toByteArray()));
            HttpResponse response = httpClient.execute(httpPost);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static String compactUUID() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.putLong(uuid.getMostSignificantBits());
        byteBuffer.putLong(uuid.getLeastSignificantBits());

        String base64 = Base64.getUrlEncoder().encodeToString(bytes);
        base64 = base64.substring(0, base64.length() - 2); // may contain underscores!
        String hex = uuid.toString().replaceAll("-", "");

//        System.out.println("base64 " + base64);
//        System.out.println("hex    " + hex);

        return hex;
    }

}
