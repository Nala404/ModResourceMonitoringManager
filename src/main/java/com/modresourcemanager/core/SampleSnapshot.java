package com.modresourcemanager.core;

import java.util.List;

public record SampleSnapshot(
        SystemMetrics system,
        List<ModMetrics> mods,
        long timestampMillis
) {
}
