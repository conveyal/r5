package com.conveyal.r5.analyst.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API data model class to hold information about the cloud compute instance a worker is running on (if any).
 */
@Deprecated
public class EC2Info {

    private static final Logger LOG = LoggerFactory.getLogger(EC2Info.class);

    public String region;
    public String instanceId;
    public String instanceType;
    public String machineImage;
    public String privateIp;

    // No-arg constructor for deserialization.
    public EC2Info() { }

}
