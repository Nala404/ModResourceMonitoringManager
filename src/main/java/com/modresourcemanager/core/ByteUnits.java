package com.modresourcemanager.core;

import java.util.Locale;

public final class ByteUnits {
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private ByteUnits() {
    }

    public static String format(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    public static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "N/A";
        }
        return format(bytesPerSecond) + "/s";
    }

    public static String formatMega(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.1f M", bytes / (1024.0 * 1024.0));
    }

    public static String formatMegaRate(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "N/A";
        }
        return formatMega(bytesPerSecond) + "/s";
    }
}
