package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.broker.WorkerCategory;
import com.conveyal.r5.common.R5Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashSet;
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
    public double tasksPerMinute;
    @JsonUnwrapped(prefix = "ec2")
    public EC2Info ec2;
    public long jvmStartTime;
    public long jvmUptime;
    public String jvmName;
    public String jvmVendor;
    public String jvmVersion;

    /** No-arg constructor used when deserializing. */
    public WorkerStatus() { }

    /**
     * Call this method to fill in the fields of the WorkerStatus object.
     * This is not done in the constructor because this class is intended to hold deserialized data, and therefore
     * needs a minimalist no-arg constructor.
     */
    public void loadStatus(AnalystWorker worker) {

        workerName = "R5";
        workerVersion = R5Version.describe;
        workerId = worker.machineId;
        networks = worker.transportNetworkCache.getLoadedNetworkIds();
        scenarios = worker.transportNetworkCache.getAppliedScenarios();
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
