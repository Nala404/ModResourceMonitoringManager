package com.modresourcemanager.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MetricsSampler {
    // 仅在界面打开时启用的低频采样器，避免静默状态消耗 CPU。
    private static final int MAX_STACK_DEPTH = 32;

    private final ModIndex index;
    private final WindowsGpuMonitor gpuMonitor = new WindowsGpuMonitor();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final Map<Long, ThreadPoint> previousPoints = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ModResourceManager-Sampler");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MetricRingBuffer<SampleSnapshot> history = new MetricRingBuffer<>(300);
    private volatile ModConfig config = new ModConfig();
    private volatile SystemMetrics system = emptySystem();
    private volatile List<ModMetrics> mods = List.of();
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long lastWallNanos = System.nanoTime();
    private ScheduledFuture<?> scheduledFuture;
    private Object allocatedBean;
    private Method allocatedMethod;

    public MetricsSampler(ModIndex index) {
        this.index = index;
        initializeThreadBeans();
    }

    public synchronized void start(ModConfig config) {
        stop();
        this.config = ModConfig.sanitize(config);
        this.history = new MetricRingBuffer<>(this.config.historyPoints);
        this.paused = false;
        this.previousPoints.clear();
        this.lastWallNanos = System.nanoTime();
        index.refresh();
        sampleNow();
        scheduledFuture = executor.scheduleAtFixedRate(this::sampleSafely, this.config.sampleIntervalMs, this.config.sampleIntervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    public synchronized void stop() {
        running = false;
        paused = false;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    public synchronized void restart(ModConfig config) {
        start(config);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public synchronized void setMonitoringEnabled(boolean enabled) {
        this.paused = !enabled;
        if (enabled) {
            if (scheduledFuture == null) {
                previousPoints.clear();
                lastWallNanos = System.nanoTime();
                executor.execute(() -> {
                    index.refresh();
                    sampleNow();
                });
                scheduledFuture = executor.scheduleAtFixedRate(
                        this::sampleSafely,
                        this.config.sampleIntervalMs,
                        this.config.sampleIntervalMs,
                        TimeUnit.MILLISECONDS
                );
            }
        } else if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isRunning() {
        return running;
    }

    public ModIndex index() {
        return index;
    }

    public ModConfig config() {
        return config;
    }

    public SystemMetrics system() {
        return system;
    }

    public List<ModMetrics> mods() {
        return mods;
    }

    public MetricRingBuffer<SampleSnapshot> history() {
        return history;
    }

    public synchronized void refreshNow() {
        index.refresh();
        sampleNow();
    }

    private void sampleSafely() {
        try {
            if (!paused) {
                sampleNow();
            }
        } catch (RuntimeException ignored) {
            // A single failed sample must not stop the monitor.
        }
    }

    public synchronized void sampleNow() {
        long now = System.nanoTime();
        double wallSeconds = Math.max(0.001, (now - lastWallNanos) / 1_000_000_000.0);
        lastWallNanos = now;

        Map<Long, Thread> threadsById = new HashMap<>();
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Thread thread : stacks.keySet()) {
            threadsById.put(thread.getId(), thread);
        }

        Map<String, double[]> cpuByGroup = new HashMap<>();
        Map<String, long[]> allocByGroup = new HashMap<>();
        Map<String, Integer> threadCounts = new HashMap<>();

        long[] threadIds = threadBean.getAllThreadIds();
        for (long threadId : threadIds) {
            ThreadInfo info = threadBean.getThreadInfo(threadId, MAX_STACK_DEPTH);
            if (info == null) {
                continue;
            }

            long cpuNanos = threadBean.getThreadCpuTime(threadId);
            long allocatedBytes = readAllocatedBytes(threadId);
            ThreadPoint previous = previousPoints.get(threadId);
            previousPoints.put(threadId, new ThreadPoint(cpuNanos, allocatedBytes));

            long cpuDelta = previous != null && cpuNanos >= 0L && previous.cpuNanos() >= 0L ? cpuNanos - previous.cpuNanos() : 0L;
            long allocDelta = previous != null && allocatedBytes >= 0L && previous.allocatedBytes() >= 0L ? allocatedBytes - previous.allocatedBytes() : 0L;

            Thread thread = threadsById.get(threadId);
            String group = index.resolveGroup(info.getStackTrace(), thread);
            addCpu(cpuByGroup, group, Math.max(0L, cpuDelta));
            addAlloc(allocByGroup, group, Math.max(0L, allocDelta));
            threadCounts.merge(group, 1, Integer::sum);
        }

        int processors = Runtime.getRuntime().availableProcessors();
        double perCoreNanos = wallSeconds * 1_000_000_000.0;
        double processCpuPercent = readProcessCpuPercent();
        long totalCpuNanos = cpuByGroup.values().stream().mapToLong(values -> (long) values[0]).sum();
        Map<String, double[]> normalizedCpu = new HashMap<>();
        Map<String, long[]> normalizedAlloc = new HashMap<>();
        for (Map.Entry<String, double[]> entry : cpuByGroup.entrySet()) {
            String group = entry.getKey();
            double rawCpuNanos = entry.getValue()[0];
            double normalized;
            if (totalCpuNanos > 0L && processCpuPercent > 0.0) {
                normalized = processCpuPercent * rawCpuNanos / totalCpuNanos;
            } else {
                normalized = processors <= 0 ? 0.0 : rawCpuNanos * 100.0 / perCoreNanos / processors;
            }
            normalizedCpu.put(group, new double[]{normalized});
            normalizedAlloc.put(group, new long[]{Math.round(allocByGroup.getOrDefault(group, new long[]{0L})[0] / wallSeconds)});
        }

        index.applyAttribution(normalizedCpu, normalizedAlloc, threadCounts);
        mods = index.all();
        system = collectSystemMetrics();
        history.add(new SampleSnapshot(system, List.copyOf(mods), System.currentTimeMillis()));
    }

    private void addCpu(Map<String, double[]> map, String key, long value) {
        map.compute(key, (ignored, current) -> {
            if (current == null) {
                return new double[]{value};
            }
            current[0] += value;
            return current;
        });
    }

    private void addAlloc(Map<String, long[]> map, String key, long value) {
        map.compute(key, (ignored, current) -> {
            if (current == null) {
                return new long[]{value};
            }
            current[0] += value;
            return current;
        });
    }

    private SystemMetrics collectSystemMetrics() {
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapCommitted = memoryBean.getHeapMemoryUsage().getCommitted();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
        long nonHeapCommitted = memoryBean.getNonHeapMemoryUsage().getCommitted();

        long gcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcTime = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        int threadCount = threadBean.getThreadCount();
        long systemMemoryTotal = osBean.getTotalMemorySize();
        long systemMemoryFree = osBean.getFreeMemorySize();
        long systemMemoryUsed = systemMemoryTotal > 0L ? Math.max(0L, systemMemoryTotal - systemMemoryFree) : -1L;

        double processCpu = readProcessCpuPercent() / 100.0;
        if (Double.isNaN(processCpu) || processCpu < 0.0) {
            processCpu = 0.0;
        }
        GpuReading gpu = gpuMonitor.read(GpuSource.fromString(config.gpuSource));

        return new SystemMetrics(
                processCpu * 100.0,
                heapUsed,
                heapCommitted,
                heapMax,
                nonHeapUsed,
                nonHeapCommitted,
                gcCount,
                gcTime,
                threadCount,
                systemMemoryUsed,
                systemMemoryTotal,
                gpu,
                System.currentTimeMillis()
        );
    }

    private double readProcessCpuPercent() {
        double processCpu = osBean.getProcessCpuLoad();
        if (Double.isNaN(processCpu) || processCpu < 0.0) {
            return 0.0;
        }
        return processCpu * 100.0;
    }

    private void initializeThreadBeans() {
        try {
            threadBean.setThreadCpuTimeEnabled(true);
        } catch (SecurityException ignored) {
        }
        try {
            Class<?> comSunThreadBean = Class.forName("com.sun.management.ThreadMXBean");
            if (comSunThreadBean.isInstance(threadBean)) {
                allocatedBean = threadBean;
                allocatedMethod = comSunThreadBean.getMethod("getThreadAllocatedBytes", long.class);
                allocatedMethod.setAccessible(true);
                Method enableMethod = comSunThreadBean.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                enableMethod.invoke(threadBean, true);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            allocatedBean = null;
            allocatedMethod = null;
        }
    }

    private long readAllocatedBytes(long threadId) {
        if (allocatedBean == null || allocatedMethod == null) {
            return -1L;
        }
        try {
            Object value = allocatedMethod.invoke(allocatedBean, threadId);
            return value instanceof Number number ? number.longValue() : -1L;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1L;
        }
    }

    private static SystemMetrics emptySystem() {
        return new SystemMetrics(0.0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, -1L, -1L, GpuReading.unavailable(), System.currentTimeMillis());
    }

    private record ThreadPoint(long cpuNanos, long allocatedBytes) {
    }
}
