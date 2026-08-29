package com.modresourcemanager.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModMetrics {
    private final String id;
    private final String name;
    private final String version;
    private final List<String> authors;
    private final String description;
    private final boolean pseudo;
    private final long diskBytes;
    private final int classCount;
    private volatile double cpuPercent;
    private volatile long allocatedBytesPerSecond;
    private volatile long estimatedHeapBytes;
    private volatile int threadCount;

    public ModMetrics(
            String id,
            String name,
            String version,
            List<String> authors,
            String description,
            boolean pseudo,
            long diskBytes,
            int classCount
    ) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.authors = Collections.unmodifiableList(new ArrayList<>(authors == null ? List.of() : authors));
        this.description = description == null ? "" : description;
        this.pseudo = pseudo;
        this.diskBytes = diskBytes;
        this.classCount = classCount;
    }

    public void updateDynamic(double cpuPercent, long allocatedBytesPerSecond, int threadCount) {
        this.cpuPercent = Math.max(0.0, cpuPercent);
        this.allocatedBytesPerSecond = Math.max(0L, allocatedBytesPerSecond);
        this.threadCount = Math.max(0, threadCount);
        this.estimatedHeapBytes = (long) classCount * 2048L + allocatedBytesPerSecond;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public List<String> authors() {
        return authors;
    }

    public String description() {
        return description;
    }

    public boolean pseudo() {
        return pseudo;
    }

    public long diskBytes() {
        return diskBytes;
    }

    public int classCount() {
        return classCount;
    }

    public double cpuPercent() {
        return cpuPercent;
    }

    public long allocatedBytesPerSecond() {
        return allocatedBytesPerSecond;
    }

    public long estimatedHeapBytes() {
        return estimatedHeapBytes;
    }

    public int threadCount() {
        return threadCount;
    }
}
