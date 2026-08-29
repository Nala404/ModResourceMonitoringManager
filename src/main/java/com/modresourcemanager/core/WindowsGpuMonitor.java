package com.modresourcemanager.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WindowsGpuMonitor {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private GpuSource lastSource;
    private GpuReading cached = GpuReading.unavailable();
    private long cacheValidUntilNanos;

    public synchronized GpuReading read(GpuSource source) {
        long now = System.nanoTime();
        if (lastSource == source && now < cacheValidUntilNanos) {
            return cached;
        }

        GpuReading result = readUncached(source);
        lastSource = source;
        cached = result;
        cacheValidUntilNanos = now + CACHE_TTL_NANOS;
        return result;
    }

    private static GpuReading readUncached(GpuSource source) {
        if (source == GpuSource.OFF) {
            return GpuReading.unavailable();
        }

        if (source == GpuSource.NVIDIA || source == GpuSource.AUTO) {
            String nvidiaOutput = runNvidiaQuery();
            GpuReading nvidia = parseNvidiaOutput(nvidiaOutput);
            if (nvidia.available()) {
                return nvidia;
            }
        }

        if (source == GpuSource.PDH || source == GpuSource.AUTO) {
            double utilization = readPdhUtilization();
            if (utilization >= 0.0) {
                return new GpuReading(true, "Windows GPU", utilization, -1L, -1L, -1.0);
            }
        }

        return GpuReading.unavailable();
    }

    private static String runNvidiaQuery() {
        return runCommand(
                List.of(
                        "nvidia-smi",
                        "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                        "--format=csv,noheader,nounits"
                ),
                5
        );
    }

    private static double readPdhUtilization() {
        String output = runCommand(
                List.of(
                        "powershell",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "$s = Get-Counter '\\GPU Engine(*engtype_3D)\\Utilization Percentage' -ErrorAction SilentlyContinue; if ($s) { [math]::Round(($s.CounterSamples | Measure-Object -Property CookedValue -Maximum).Maximum, 1) }"
                ),
                5
        );
        if (output == null) {
            return -1.0;
        }
        return parseFirstNumber(output);
    }

    static GpuReading parseNvidiaOutput(String output) {
        if (output == null || output.isBlank()) {
            return GpuReading.unavailable();
        }
        String firstLine = output.lines().findFirst().orElse("").trim();
        if (firstLine.isBlank()) {
            return GpuReading.unavailable();
        }

        String[] parts = firstLine.split(",");
        if (parts.length < 5) {
            return GpuReading.unavailable();
        }

        String name = joinName(parts, parts.length - 4);
        double utilization = parseDouble(parts[parts.length - 4]);
        long memoryUsed = parseLong(parts[parts.length - 3]);
        long memoryTotal = parseLong(parts[parts.length - 2]);
        double temperature = parseDouble(parts[parts.length - 1]);

        if (utilization < 0.0) {
            return GpuReading.unavailable();
        }

        return new GpuReading(
                true,
                name,
                utilization,
                memoryUsed < 0L ? -1L : memoryUsed * 1024L * 1024L,
                memoryTotal < 0L ? -1L : memoryTotal * 1024L * 1024L,
                temperature
        );
    }

    private static String joinName(String[] parts, int endExclusive) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < endExclusive; i++) {
            if (i > 0) {
                name.append(',');
            }
            name.append(parts[i].trim());
        }
        return name.toString();
    }

    private static String runCommand(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            return output.toString();
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static double parseFirstNumber(String value) {
        if (value == null) {
            return -1.0;
        }
        Matcher matcher = NUMBER.matcher(value);
        if (!matcher.find()) {
            return -1.0;
        }
        return parseDouble(matcher.group().replace(',', '.'));
    }

    private static double parseDouble(String value) {
        if (value == null) {
            return -1.0;
        }
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private static long parseLong(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
