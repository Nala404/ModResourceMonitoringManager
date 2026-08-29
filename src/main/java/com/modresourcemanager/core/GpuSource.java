package com.modresourcemanager.core;

import java.util.Locale;

public enum GpuSource {
    AUTO,
    NVIDIA,
    PDH,
    OFF;

    public static GpuSource fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
