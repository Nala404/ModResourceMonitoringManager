package com.modresourcemanager.core;

public record GpuReading(
        boolean available,
        String name,
        double utilizationPercent,
        long memoryUsedBytes,
        long memoryTotalBytes,
        double temperatureC
) {
    public static GpuReading unavailable() {
        return new GpuReading(false, "N/A", -1.0, -1L, -1L, -1.0);
    }
}
