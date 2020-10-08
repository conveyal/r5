package com.conveyal.r5.analyst.cluster;

import com.amazonaws.util.EC2MetadataUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * API data model class to hold information about the ec2 instance a worker is running on (if any).
 */
public class EC2Info {

    private static final Logger LOG = LoggerFactory.getLogger(EC2Info.class);

    public String region;
    public String instanceId;
    public String instanceType;
    public String machineImage;
    public String privateIp;

    /** Empty constructor, which will be called during deserialization from JSON. */
    public EC2Info() { }

    /** This will attempt to retrieve metadata about the instance this code is running on. */
    public void fetchMetadata() {
        HttpClient httpClient = HttpClients.createDefault();
        RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(1000).build();
        HttpUriRequest get = RequestBuilder.get().setUri(EC2MetadataUtils.getHostAddressForEC2MetadataService())
                .setConfig(requestConfig).build();
        try {
            httpClient.execute(get);
            machineImage = EC2MetadataUtils.getAmiId();
            instanceType = EC2MetadataUtils.getInstanceType();
            instanceId = EC2MetadataUtils.getInstanceId();
            // There is a method to get a Region object, but stick to strings for deserialization simplicity.
            region = EC2MetadataUtils.getEC2InstanceRegion();
            // IP address fetching should really not be tied to EC2 but for now this lets us get a useable IP.
            privateIp = EC2MetadataUtils.getPrivateIpAddress();
            //EC2MetadataUtils.getInstanceInfo();
        } catch (IOException ex) {
            LOG.warn("Connection to metadata URL failed, probably not running on EC2.");
        }
    }

}
