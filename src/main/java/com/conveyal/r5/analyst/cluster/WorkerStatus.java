package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.SoftwareVersion;
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
    private static final int LEGACY_WORKER_MAX_TASKS = 16;
    public static final int LEGACY_WORKER_MAX_POLL_INTERVAL_SECONDS = 2 * 60;

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

    /**
     * Then maximum number of tasks the broker should send to this worker. May be zero if its work queue is full.
     * Default value determines the number of tasks to send to older workers that don't send this value when they poll.
     */
    public int maxTasksRequested = LEGACY_WORKER_MAX_TASKS;

    /**
     * The maximum amount of time the worker will wait before polling again. After this much time passes the backend
     * may consider the worker lost or shut down. The backend should be somewhat lenient here as there could be delays
     * due to connection setup, API contention etc. The default value reflects how how long the backend should wait to
     * hear from older workers that don't send this value.
     */
    public int pollIntervalSeconds = LEGACY_WORKER_MAX_POLL_INTERVAL_SECONDS;

    /** No-arg constructor used when deserializing. */
    public WorkerStatus() { }

    /** Constructor that fills in all the fields with information about the machine it's running on. */
    public WorkerStatus (AnalysisWorker worker) {

        workerName = "R5";
        workerVersion = SoftwareVersion.instance.version;
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

        if (ec2 != null && ec2.privateIp != null) {
            // Give priority to the private IP address if running in cloud compute environment.
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
