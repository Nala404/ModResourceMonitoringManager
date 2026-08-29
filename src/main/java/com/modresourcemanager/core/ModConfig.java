package com.modresourcemanager.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int sampleIntervalMs = 1000;
    public int historyPoints = 300;
    public boolean showGroups = true;
    public String sortColumn = "cpu";
    public boolean sortDescending = false;
    public String gpuSource = "auto";
    public Map<String, String> graphColors = defaultColors();

    public static ModConfig load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return sanitize(new ModConfig());
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ModConfig config = GSON.fromJson(json, ModConfig.class);
            return sanitize(config == null ? new ModConfig() : config);
        } catch (Exception ignored) {
            return new ModConfig();
        }
    }

    public void save(Path path) throws IOException {
        if (path == null) {
            return;
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, GSON.toJson(sanitize(this)), StandardCharsets.UTF_8);
    }

    public static ModConfig sanitize(ModConfig config) {
        config.sampleIntervalMs = clamp(config.sampleIntervalMs, 250, 10_000);
        config.historyPoints = clamp(config.historyPoints, 30, 3_600);
        config.gpuSource = GpuSource.fromString(config.gpuSource).name().toLowerCase();
        if (config.graphColors == null || config.graphColors.isEmpty()) {
            config.graphColors = defaultColors();
        } else {
            Map<String, String> merged = defaultColors();
            merged.putAll(config.graphColors);
            config.graphColors = merged;
        }
        return config;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, String> defaultColors() {
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("cpu", "#4FC3F7");
        colors.put("memory", "#81C784");
        colors.put("gpu", "#BA68C8");
        colors.put("allocation", "#FFB74D");
        return colors;
    }
}
