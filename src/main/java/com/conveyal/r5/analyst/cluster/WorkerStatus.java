package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.BackendVersion;
import com.conveyal.r5.analyst.WorkerCategory;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is an API data model object, used by workers to send information about themselves to the broker as JSON.
 */
public class WorkerStatus {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerStatus.class);

    public String architecture;
    public int processors;
    public double loadAverage;
    public String osName;
    public String osVersion;
    public long memoryMax;
    public long memoryTotal;
    public long memoryFree;
    public String workerName;
    public String workerVersion;
    public String workerId;
    public Set<String> networks = new HashSet<>();
    public Set<String> scenarios = new HashSet<>();
    public double secondsSinceLastPoll;
    public Map<String, Integer> tasksPerMinuteByJobId;
    @JsonUnwrapped(prefix = "ec2")
    public EC2Info ec2;
    public long jvmStartTime;
    public long jvmUptime;
    public String jvmName;
    public String jvmVendor;
    public String jvmVersion;
    public String ipAddress;
    public List<RegionalWorkResult> results;

    /** No-arg constructor used when deserializing. */
    public WorkerStatus() { }

    /** Constructor that fills in all the fields with information about the machine it's running on. */
    public WorkerStatus (AnalysisWorker worker) {

        workerName = "R5";
        workerVersion = BackendVersion.instance.version;
        workerId = worker.machineId; // TODO overwrite with cloud provider (EC2) machine ID in a generic way

        // Eventually we'll want to report all networks the worker has loaded, to give the backend hints about what kind
        // of tasks the worker is ready to work on immediately. This is made more complicated by the fact that workers are
        // started up with no networks loaded, but with the intent for them to work on a particular job. So currently the
        // workers just report which network they were started up for, and this method is not used.
        // In the future, workers should just report an empty set of loaded networks, and the back end should strategically
        // send them tasks when they come on line to assign them to networks as needed. But this will require a new
        // mechanism to fairly allocate the workers to jobs.
        // networks = worker.networkPreloader.transportNetworkCache.getLoadedNetworkIds();
        // For now we report a single network, even before it's loaded.
        networks = Sets.newHashSet(worker.networkId);
        scenarios = worker.networkPreloader.transportNetworkCache.getAppliedScenarios();
        ec2 = worker.ec2info;

        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        architecture = operatingSystemMXBean.getArch();
        processors = operatingSystemMXBean.getAvailableProcessors();
        loadAverage = operatingSystemMXBean.getSystemLoadAverage();
        osName = operatingSystemMXBean.getName();
        osVersion = operatingSystemMXBean.getVersion();

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        jvmStartTime = runtimeMXBean.getStartTime() / 1000;
        jvmUptime = runtimeMXBean.getUptime() / 1000;
        jvmName = runtimeMXBean.getVmName();
        jvmVendor = runtimeMXBean.getVmVendor();
        jvmVersion = runtimeMXBean.getVmVersion();

        Runtime runtime = Runtime.getRuntime();
        memoryMax = runtime.maxMemory();
        memoryTotal = runtime.totalMemory();
        memoryFree = runtime.freeMemory();

        if (ec2.privateIp != null) {
            // Give priority to the private IP address if running on EC2
            ipAddress = ec2.privateIp;
        } else {
            // Get whatever is the default IP address
            // FIXME this appears to be favoring IPv6 on MacOS which makes for buggy URLs
            try {
                ipAddress = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                ipAddress = "127.0.0.1";
            }
        }
    }

    /**
     * Return a single network ID or null, rather than a list of loaded network IDs.
     * This is a stopgap measure until workers can cache more than one loaded network.
     */
    @JsonIgnore
    private String getPreferredNetwork() {
        if (networks == null || networks.isEmpty()) return null;
        return networks.iterator().next();
    }

    /**
     * Return a category for the worker which inherently has only one network ID (or null).
     * By category we mean a tuple of (network affinity, r5 version).
     * This is a stopgap measure until workers can cache more than one loaded network.
     */
    @JsonIgnore
    public WorkerCategory getWorkerCategory() {
        return new WorkerCategory(getPreferredNetwork(), workerVersion);
    }

}
