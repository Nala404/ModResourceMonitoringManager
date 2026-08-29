package com.modresourcemanager.core;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class ModIndex {
    // Fabric 暴露的 Mod 与 mods 目录兜底扫描都会汇总到这里。
    private final Map<String, ModMetrics> byId = new LinkedHashMap<>();
    private final Map<String, String> classNameGroupCache = new ConcurrentHashMap<>();
    private final Map<String, String> rootPathToModId = new HashMap<>();

    public synchronized void refresh() {
        byId.clear();
        classNameGroupCache.clear();
        rootPathToModId.clear();
        Set<String> knownRootPaths = new HashSet<>();

        Collection<ModContainer> containers;
        try {
            containers = FabricLoader.getInstance().getAllMods();
        } catch (RuntimeException ignored) {
            containers = List.of();
        }

        for (ModContainer container : containers) {
            try {
                ModMetadata metadata = container.getMetadata();
                String id = metadata.getId();
                String name = metadata.getName();
                String version = metadata.getVersion().getFriendlyString();
                String description = metadata.getDescription();
                List<String> authors = metadata.getAuthors().stream()
                        .map(Person::getName)
                        .filter(authorName -> authorName != null && !authorName.isBlank())
                        .toList();
                long diskBytes = sumRootPaths(container.getRootPaths());
                int classCount = countClasses(container.getRootPaths());

                byId.put(id, new ModMetrics(
                        id,
                        name,
                        version,
                        authors,
                        description,
                        false,
                        diskBytes,
                        classCount
                ));

                for (Path root : container.getRootPaths()) {
                    if (root != null) {
                        String normalized = normalizePath(root);
                        knownRootPaths.add(normalized);
                        rootPathToModId.put(normalized, id);
                    }
                }
            } catch (RuntimeException ignored) {
                // A malformed or unavailable mod must not stop discovery of the rest.
            }
        }

        discoverModsFolder(knownRootPaths);

        byId.put(ThreadAttribution.GAME, new ModMetrics(
                ThreadAttribution.GAME,
                "Minecraft / Game",
                "vanilla",
                List.of(),
                "Minecraft and loader runtime",
                true,
                -1L,
                0
        ));
        byId.put(ThreadAttribution.SYSTEM, new ModMetrics(
                ThreadAttribution.SYSTEM,
                "JVM / System",
                "runtime",
                List.of(),
                "JVM, LWJGL, Netty and system threads",
                true,
                -1L,
                0
        ));
    }

    private void discoverModsFolder(Set<String> knownRootPaths) {
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (!Files.isDirectory(modsDir)) {
                return;
            }
            try (Stream<Path> stream = Files.list(modsDir)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String normalized = normalizePath(path);
                    if (knownRootPaths.contains(normalized)) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (!fileName.endsWith(".jar")) {
                        continue;
                    }
                    String id = fileName.substring(0, fileName.length() - 4);
                    if (byId.containsKey(id)) {
                        continue;
                    }
                    int classCount = countClassesInJar(path);
                    long diskBytes = pathSize(path);
                    byId.put(id, new ModMetrics(
                            id,
                            id,
                            "",
                            List.of(),
                            "",
                            false,
                            diskBytes,
                            classCount
                    ));
                    rootPathToModId.put(normalized, id);
                }
            }
        } catch (RuntimeException | IOException ignored) {
            // Folder discovery is a fallback; never block the UI.
        }
    }

    public synchronized void applyAttribution(Map<String, double[]> cpuPercentByGroup, Map<String, long[]> allocByGroup, Map<String, Integer> threadCounts) {
        for (Map.Entry<String, ModMetrics> entry : byId.entrySet()) {
            String id = entry.getKey();
            double cpu = doubleValue(cpuPercentByGroup, id, 0);
            long alloc = longValue(allocByGroup, id, 0);
            int threads = threadCounts.getOrDefault(id, 0);
            entry.getValue().updateDynamic(cpu, alloc, threads);
        }
    }

    public synchronized List<ModMetrics> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized ModMetrics byId(String id) {
        return byId.get(id);
    }

    public String resolveGroup(StackTraceElement[] stack, Thread thread) {
        if (stack == null || stack.length == 0) {
            return ThreadAttribution.GAME;
        }

        ClassLoader contextLoader = thread == null ? null : thread.getContextClassLoader();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            String classified = ThreadAttribution.classifyClassName(className);
            if (ThreadAttribution.SYSTEM.equals(classified)) {
                continue;
            }
            if (ThreadAttribution.GAME.equals(classified)) {
                continue;
            }

            String cached = classNameGroupCache.get(className);
            if (cached != null) {
                return cached;
            }

            String group = resolveClassName(className, contextLoader);
            classNameGroupCache.put(className, group);
            if (!ThreadAttribution.GAME.equals(group)) {
                return group;
            }
        }
        return ThreadAttribution.GAME;
    }

    private static double doubleValue(Map<String, double[]> map, String key, int index) {
        double[] values = map.get(key);
        return values != null && index < values.length ? values[index] : 0.0;
    }

    private static long longValue(Map<String, long[]> map, String key, int index) {
        long[] values = map.get(key);
        return values != null && index < values.length ? values[index] : 0L;
    }

    private String resolveByCodeSource(Class<?> clazz) {
        try {
            if (clazz.getProtectionDomain() == null || clazz.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            java.net.URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            return rootPathToModId.get(normalizePath(path));
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
            return null;
        }
    }

    private String resolveClassName(String className, ClassLoader contextLoader) {
        ClassLoader loader = contextLoader != null ? contextLoader : getClass().getClassLoader();
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            String group = resolveByClassResource(clazz);
            if (group == null) {
                group = resolveByCodeSource(clazz);
            }
            if (group != null) {
                return group;
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            // Try package-based fallback below.
        }
        return ThreadAttribution.GAME;
    }

    private String resolveByClassResource(Class<?> clazz) {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";
        try {
            java.net.URL resource = clazz.getResource(resourceName);
            if (resource == null) {
                return null;
            }
            String url = resource.toString();
            if (url.startsWith("jar:file:")) {
                int bang = url.indexOf('!');
                if (bang >= 0) {
                    url = url.substring(4, bang);
                }
            } else if (url.startsWith("file:")) {
                // Already a file URL.
            } else {
                return null;
            }
            Path path = Path.of(java.net.URI.create(url));
            return rootPathToModId.get(normalizePath(path));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static long sumRootPaths(List<Path> roots) {
        long total = 0L;
        for (Path root : roots) {
            total += pathSize(root);
        }
        return total;
    }

    private static long pathSize(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    return stream.filter(Files::isRegularFile).mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    }).sum();
                }
            }
        } catch (IOException ignored) {
        }
        return 0L;
    }

    private static int countClasses(List<Path> roots) {
        int count = 0;
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            try {
                if (Files.isRegularFile(root)) {
                    count += countClassesInJar(root);
                } else if (Files.isDirectory(root)) {
                    try (Stream<Path> stream = Files.walk(root)) {
                        count += stream.filter(p -> p.getFileName().toString().endsWith(".class")).mapToInt(p -> 1).sum();
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    private static int countClassesInJar(Path jar) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
            return (int) jarFile.stream().filter(entry -> entry.getName().endsWith(".class")).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    public static Comparator<ModMetrics> comparator(String column, boolean descending) {
        Comparator<ModMetrics> comparator = switch (column == null ? "name" : column) {
            case "cpu" -> Comparator.comparingDouble(ModMetrics::cpuPercent);
            case "memory" -> Comparator.comparingLong(ModMetrics::allocatedBytesPerSecond);
            case "disk" -> Comparator.comparingLong(ModMetrics::diskBytes);
            case "classes" -> Comparator.comparingInt(ModMetrics::classCount);
            case "threads" -> Comparator.comparingInt(ModMetrics::threadCount);
            default -> Comparator.comparing(ModMetrics::name, String.CASE_INSENSITIVE_ORDER);
        };
        return descending ? comparator.reversed() : comparator;
    }
}
