package com.modresourcemanager.core;

public record SystemMetrics(
        double processCpuPercent,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long nonHeapCommittedBytes,
        long gcCount,
        long gcTimeMs,
        int processThreadCount,
        long systemMemoryUsedBytes,
        long systemMemoryTotalBytes,
        GpuReading gpu,
        long timestampMillis
) {
    public double heapUsagePercent() {
        if (heapMaxBytes <= 0) {
            return 0.0;
        }
        return Math.min(100.0, heapUsedBytes * 100.0 / heapMaxBytes);
    }
}
