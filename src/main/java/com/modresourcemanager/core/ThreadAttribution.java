package com.modresourcemanager.core;

public final class ThreadAttribution {
    public static final String GAME = "game";
    public static final String SYSTEM = "system";

    private ThreadAttribution() {
    }

    public static boolean isSystemClass(String className) {
        if (className == null) {
            return false;
        }
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("org.graalvm.")
                || className.startsWith("org.lwjgl.")
                || className.startsWith("io.netty.");
    }

    public static boolean isGameClass(String className) {
        if (className == null) {
            return true;
        }
        return className.startsWith("net.minecraft.")
                || className.startsWith("com.mojang.")
                || className.startsWith("net.fabricmc.loader.");
    }

    public static String classifyClassName(String className) {
        if (isSystemClass(className)) {
            return SYSTEM;
        }
        if (isGameClass(className)) {
            return GAME;
        }
        return null;
    }
}
