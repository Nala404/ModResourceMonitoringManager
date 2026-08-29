package com.modresourcemanager.gui;

import com.modresourcemanager.core.ByteUnits;
import com.modresourcemanager.core.GpuReading;
import com.modresourcemanager.core.MetricRingBuffer;
import com.modresourcemanager.core.MetricsSampler;
import com.modresourcemanager.core.ModConfig;
import com.modresourcemanager.core.ModIndex;
import com.modresourcemanager.core.ModMetrics;
import com.modresourcemanager.core.SampleSnapshot;
import com.modresourcemanager.core.SystemMetrics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class ResourceManagerScreen extends Screen {
    // 任务管理器风格界面，Game 分组固定置顶并参与统一滚动。
    private static final int MAX_CONTENT_WIDTH = 520;
    private static final int MAX_CONTENT_HEIGHT = 400;
    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_Y_OFFSET = 52;
    private static final int[] COLUMN_X_OFFSETS = {30, 205, 270, 355, 435};

    private final MetricsSampler sampler;
    private ModConfig config;
    private final Runnable onCloseAction;
    private int selectedTab;
    private String sortColumn;
    private boolean sortDescending;
    private int scrollOffset;
    private ModMetrics hoveredMod;
    private long hoverStartedNanos;
    private ModMetrics lastClickedMod;
    private long lastClickedNanos;
    private boolean draggingScrollbar;
    private int scrollDragStartY;
    private int scrollDragStartOffset;
    private GameEntityStats gameStats = GameEntityStats.EMPTY;
    private long lastGameStatsNanos;
    private boolean gameDetailsExpanded = false;
    private int cachedMobs;
    private int cachedPlayers;
    private int cachedItems;
    private int cachedProjectiles;
    private int cachedRedstone;
    private long cachedGameSnapshotNanos;

    public ResourceManagerScreen(MetricsSampler sampler, ModConfig config, Runnable onCloseAction) {
        super(Component.translatable("screen.modresourcemanager.title"));
        this.sampler = sampler;
        this.config = ModConfig.sanitize(config);
        this.onCloseAction = onCloseAction;
        this.sortColumn = this.config.sortColumn;
        this.sortDescending = this.config.sortDescending;
    }

    @Override
    public void onClose() {
        onCloseAction.run();
        super.onClose();
    }

    @Override
    protected void init() {
        clearWidgets();

        int left = left();
        int top = top();
        int contentWidth = contentWidth();
        int contentHeight = contentHeight();

        addTabButtons(left, top);

    }

    private void addTabButtons(int left, int top) {
        String[] keys = {
                "screen.modresourcemanager.tab.mods",
                "screen.modresourcemanager.tab.performance"
        };
        int x = left + 8;
        for (int i = 0; i < keys.length; i++) {
            int tab = i;
            int width = 62;
            addRenderableWidget(Button.builder(
                    Component.translatable(keys[i]),
                    button -> setTab(tab)
            ).bounds(x, top + 24, width, 20).build());
            x += width + 4;
        }
    }

    private void setTab(int tab) {
        selectedTab = tab;
        clearWidgets();
        init();
    }

    private void togglePaused() {
        sampler.setPaused(!sampler.isPaused());
        clearWidgets();
        init();
    }

    private void reloadConfig() {
        config = ModConfig.load(ModConfigPath.resolve());
        sampler.restart(config);
        clearWidgets();
        init();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = left();
        int top = top();
        int contentWidth = contentWidth();
        int contentHeight = contentHeight();

        fillRoundedPanel(graphics, left, top, contentWidth, contentHeight, 0xD0101010, 8);
        graphics.fill(left, top, left + contentWidth, top + 1, 0xFF808080);
        graphics.fill(left, top + contentHeight - 1, left + contentWidth, top + contentHeight, 0xFF808080);

        graphics.centeredText(font, Component.translatable("screen.modresourcemanager.title").getString(), left + contentWidth / 2, top + 8, 0xFFFFFFFF);

        if (selectedTab == 0) {
            updateGameStats();
            renderModsTab(graphics, left, top, contentWidth, contentHeight);
        } else if (selectedTab == 1) {
            renderPerformanceTab(graphics, left, top, contentWidth, contentHeight);
        } else {
            renderHistoryTab(graphics, left, top, contentWidth, contentHeight);
        }

        int selectedTabX = left + 8 + selectedTab * 66;
        graphics.fill(selectedTabX, top + 24, selectedTabX + 62, top + 44, 0xFF3A4B52);
        graphics.fill(selectedTabX, top + 24, selectedTabX + 62, top + 25, 0xFF4FC3F7);
        graphics.fill(selectedTabX, top + 43, selectedTabX + 62, top + 44, 0xFF4FC3F7);
        graphics.fill(selectedTabX, top + 24, selectedTabX + 1, top + 44, 0xFF4FC3F7);
        graphics.fill(selectedTabX + 61, top + 24, selectedTabX + 62, top + 44, 0xFF4FC3F7);

        updateHoveredMod(mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (hoveredMod != null && System.nanoTime() - hoverStartedNanos >= 500_000_000L) {
            renderHoverTooltip(graphics, hoveredMod, mouseX, mouseY);
        }

    }


    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Keep the world visible outside the inventory-style panel.
    }

    private void renderModsTab(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        int headerY = top + HEADER_Y_OFFSET;
        String[] headers = {
                "screen.modresourcemanager.column.name",
                "screen.modresourcemanager.column.cpu",
                "screen.modresourcemanager.column.memory",
                "screen.modresourcemanager.column.disk",
                "screen.modresourcemanager.column.classes"
        };
        String[] columns = {"name", "cpu", "memory", "disk", "classes"};
        for (int i = 0; i < headers.length; i++) {
            String header = Component.translatable(headers[i]).getString();
            if (columns[i].equals(sortColumn)) {
                header += sortDescending ? " ▼" : " ▲";
            }
            graphics.text(font, header, left + COLUMN_X_OFFSETS[i], headerY, 0xFFFFFFFF);
        }

        List<DisplayRow> rows = buildRows();
        int maxRows = maxVisibleRows();
        int maxScroll = Math.max(0, rows.size() - maxRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        int start = Math.min(scrollOffset, Math.max(0, rows.size() - 1));
        int y = headerY + 18;
        int listHeight = maxRows * ROW_HEIGHT;
        graphics.fill(left + 6, y - 2, left + contentWidth - 6, y + listHeight, 0xD0121212);
        graphics.fill(left + 6, y - 2, left + contentWidth - 6, y - 1, 0xFF5A5A5A);
        graphics.fill(left + 6, y + listHeight, left + contentWidth - 6, y + listHeight + 1, 0xFF5A5A5A);
        int end = Math.min(rows.size(), start + maxRows);
        for (int i = start; i < end; i++) {
            DisplayRow row = rows.get(i);
            int rowColor = ((i - start) % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            graphics.fill(left + 8, y, left + contentWidth - 8, y + ROW_HEIGHT - 1, rowColor);
            if (row.gameHeader()) {
                String fold = gameDetailsExpanded ? "[-]" : "[+]";
                graphics.text(font, fold, left + 7, y + 2, 0xFF4FC3F7);
                ModMetrics gameMod = row.mod();
                graphics.text(font, row.text(), left + COLUMN_X_OFFSETS[0], y + 2, 0xFF4FC3F7);
                if (gameMod != null) {
                    graphics.text(font, String.format(java.util.Locale.ROOT, "%.1f%%", gameMod.cpuPercent()), left + COLUMN_X_OFFSETS[1], y + 2, 0xFFFFFFFF);
                    graphics.text(font, ByteUnits.formatMega(gameMod.estimatedHeapBytes()), left + COLUMN_X_OFFSETS[2], y + 2, 0xFFFFFFFF);
                    graphics.text(font, gameMod.diskBytes() < 0 ? "-" : ByteUnits.format(gameMod.diskBytes()), left + COLUMN_X_OFFSETS[3], y + 2, 0xFFFFFFFF);
                    graphics.text(font, String.valueOf(gameMod.classCount()), left + COLUMN_X_OFFSETS[4], y + 2, 0xFFFFFFFF);
                }
            } else if (row.mod() == null) {
                graphics.text(font, row.text(), left + 10 + row.indent(), y + 2, 0xFFDDDDDD);
            } else {
                ModMetrics mod = row.mod();
                if ("modresourcemanager".equals(mod.id())) {
                    graphics.blit(Identifier.fromNamespaceAndPath("modresourcemanager", "icon"), left + 6, y + 2, 12, 12, 0.0F, 0.0F, 12.0F, 12.0F);
                }
                int nameX = left + COLUMN_X_OFFSETS[0] + row.indent();
                graphics.text(font, truncate(mod.name(), 23), nameX, y + 2, 0xFFFFFFFF);
                graphics.text(font, String.format(java.util.Locale.ROOT, "%.1f%%", mod.cpuPercent()), left + COLUMN_X_OFFSETS[1], y + 2, 0xFFFFFFFF);
                graphics.text(font, ByteUnits.formatMega(mod.estimatedHeapBytes()), left + COLUMN_X_OFFSETS[2], y + 2, 0xFFFFFFFF);
                graphics.text(font, mod.diskBytes() < 0 ? "-" : ByteUnits.format(mod.diskBytes()), left + COLUMN_X_OFFSETS[3], y + 2, 0xFFFFFFFF);
                graphics.text(font, String.valueOf(mod.classCount()), left + COLUMN_X_OFFSETS[4], y + 2, 0xFFFFFFFF);
            }
            y += ROW_HEIGHT;
        }

        if (maxScroll > 0) {
            int trackTop = headerY + 18;
            int trackHeight = maxRows * ROW_HEIGHT;
            int thumbHeight = Math.max(12, trackHeight * maxRows / rows.size());
            int thumbY = trackTop + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
            graphics.fill(left + contentWidth - 6, trackTop, left + contentWidth - 2, trackTop + trackHeight, 0x30FFFFFF);
            graphics.fill(left + contentWidth - 6, thumbY, left + contentWidth - 2, thumbY + thumbHeight, 0xA0FFFFFF);
        }

        SystemMetrics system = sampler.system();
        String summary = "CPU " + String.format(java.util.Locale.ROOT, "%.1f%%", system.processCpuPercent())
                + "  Heap " + ByteUnits.formatMega(system.heapUsedBytes())
                + " / " + ByteUnits.formatMega(system.heapMaxBytes())
                + "  Memory " + (system.systemMemoryUsedBytes() < 0 ? "-" : ByteUnits.formatMega(system.systemMemoryUsedBytes()) + " / " + ByteUnits.formatMega(system.systemMemoryTotalBytes()))
                + "  GPU " + formatGpu(system.gpu());
        graphics.text(font, summary, left + 10, top + contentHeight - 24, 0xFFBBBBBB);
    }

    private void renderGameDetails(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        collectGameSnapshot();

        int panelX = left + 8;
        int panelY = top + contentHeight - 92;
        int panelWidth = contentWidth - 16;
        int headerHeight = 16;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + headerHeight, 0xFF223038);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF4FC3F7);
        graphics.fill(panelX, panelY + headerHeight - 1, panelX + panelWidth, panelY + headerHeight, 0xFF4FC3F7);
        String title = (gameDetailsExpanded ? "[-] " : "[+] ") + "Minecraft / Game Details";
        graphics.text(font, title, panelX + 5, panelY + 3, 0xFFFFFFFF);

        if (!gameDetailsExpanded) {
            return;
        }

        int childY = panelY + headerHeight;
        String[] names = {"Mobs", "Players", "Items", "Projectiles", "Redstone/BlockEntities"};
        int[] counts = {cachedMobs, cachedPlayers, cachedItems, cachedProjectiles, cachedRedstone};
        for (int i = 0; i < names.length; i++) {
            graphics.fill(panelX, childY, panelX + panelWidth, childY + 14, (i % 2 == 0) ? 0x14FFFFFF : 0x0AFFFFFF);
            graphics.text(font, names[i], panelX + 7, childY + 2, 0xFFDDDDDD);
            graphics.text(font, String.valueOf(counts[i]), panelX + panelWidth - 40, childY + 2, 0xFFFFFFFF);
            childY += 14;
        }
    }

    private void collectGameSnapshot() {
        long now = System.nanoTime();
        if (now - cachedGameSnapshotNanos < 1_000_000_000L) {
            return;
        }
        cachedGameSnapshotNanos = now;
        cachedMobs = 0;
        cachedPlayers = minecraft.level == null ? 0 : minecraft.level.players().size();
        cachedItems = 0;
        cachedProjectiles = 0;
        cachedRedstone = minecraft.level == null ? 0 : minecraft.level.getGloballyRenderedBlockEntities().size();

        if (minecraft.level != null) {
            for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                    cachedItems++;
                } else if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
                    cachedProjectiles++;
                } else if (entity instanceof net.minecraft.world.entity.Mob) {
                    cachedMobs++;
                }
            }
        }
    }

    private void renderPerformanceTab(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        MetricRingBuffer<SampleSnapshot> history = sampler.history();
        List<SampleSnapshot> snapshots = history.snapshot();
        SystemMetrics latest = sampler.system();

        List<Double> cpu = new ArrayList<>();
        List<Double> memory = new ArrayList<>();
        List<Double> gpu = new ArrayList<>();
        for (SampleSnapshot snapshot : snapshots) {
            cpu.add(snapshot.system().processCpuPercent());
            memory.add(snapshot.system().heapUsagePercent());
            gpu.add(snapshot.system().gpu().available() ? snapshot.system().gpu().utilizationPercent() : 0.0);
        }

        int graphLeft = left + 10;
        int graphTop = top + 50;
        int graphWidth = contentWidth - 20;
        int graphHeight = Math.max(36, (contentHeight - 92) / 3 - 4);

        drawGraph(graphics, "CPU", graphLeft, graphTop, graphWidth, graphHeight, cpu, 100.0, color(config.graphColors.getOrDefault("cpu", "#4FC3F7")));
        graphTop += graphHeight + 4;
        drawGraph(graphics, "Memory", graphLeft, graphTop, graphWidth, graphHeight, memory, 100.0, color(config.graphColors.getOrDefault("memory", "#81C784")));
        graphTop += graphHeight + 4;
        drawGraph(graphics, "GPU", graphLeft, graphTop, graphWidth, graphHeight, gpu, 100.0, color(config.graphColors.getOrDefault("gpu", "#BA68C8")));

        String detail = "Heap " + ByteUnits.formatMega(latest.heapUsedBytes())
                + " / " + ByteUnits.formatMega(latest.heapCommittedBytes())
                + "  NonHeap " + ByteUnits.formatMega(latest.nonHeapUsedBytes())
                + "  Memory " + (latest.systemMemoryUsedBytes() < 0 ? "-" : ByteUnits.formatMega(latest.systemMemoryUsedBytes()) + " / " + ByteUnits.formatMega(latest.systemMemoryTotalBytes()))
                + "  GC " + latest.gcCount()
                + "  Threads " + latest.processThreadCount();
        graphics.text(font, detail, left + 10, top + contentHeight - 37, 0xFFBBBBBB);
    }

    private void renderHistoryTab(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        List<SampleSnapshot> snapshots = sampler.history().snapshot();
        int y = top + 50;
        int count = 0;
        for (int i = snapshots.size() - 1; i >= 0 && count < 8; i--) {
            SampleSnapshot snapshot = snapshots.get(i);
            SystemMetrics system = snapshot.system();
            String line = String.format(
                    java.util.Locale.ROOT,
                    "%tT  CPU %.1f%%  Heap %s  GPU %s",
                    snapshot.timestampMillis(),
                    system.processCpuPercent(),
                    ByteUnits.format(system.heapUsedBytes()),
                    formatGpu(system.gpu())
            );
            graphics.text(font, line, left + 10, y, 0xFFDDDDDD);
            y += 16;
            count++;
        }
    }

    private void drawGraph(GuiGraphicsExtractor graphics, String title, int x, int y, int width, int height, List<Double> values, double max, int color) {
        graphics.fill(x - 1, y - 1, x + width + 1, y, 0xFF5A5A5A);
        graphics.fill(x - 1, y + height, x + width + 1, y + height + 1, 0xFF5A5A5A);
        graphics.fill(x, y, x + width, y + height, 0x22000000);
        graphics.text(font, title, x, y - 9, 0xFFCCCCCC);

        if (values.isEmpty() || max <= 0.0) {
            graphics.text(font, "-", x + width - 14, y - 9, 0xFFCCCCCC);
            return;
        }

        double current = values.get(values.size() - 1);
        graphics.text(font, String.format(java.util.Locale.ROOT, "%.1f%%", current), x + width - 48, y - 9, 0xFFCCCCCC);

        int innerTop = y + 2;
        int innerBottom = y + height - 2;
        int graphHeight = Math.max(1, innerBottom - innerTop);
        int stepX = values.size() > 1 ? (width - 4) / (values.size() - 1) : 1;

        for (int i = 0; i < values.size() - 1; i++) {
            int x1 = x + 2 + i * stepX;
            int x2 = x + 2 + (i + 1) * stepX;
            int y1 = innerBottom - (int) Math.round(clamp(values.get(i), 0.0, max) / max * graphHeight);
            int y2 = innerBottom - (int) Math.round(clamp(values.get(i + 1), 0.0, max) / max * graphHeight);
            graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, color);
        }
    }

    private void fillRoundedPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color, int radius) {
        for (int i = 0; i < radius; i++) {
            int inset = radius - i;
            graphics.fill(x + inset, y + i, x + width - inset, y + i + 1, color);
            graphics.fill(x + inset, y + height - i - 1, x + width - inset, y + height - i, color);
        }
        graphics.fill(x, y + radius, x + width, y + height - radius, color);
    }

    private List<ModMetrics> sortedMods() {
        List<ModMetrics> result = new ArrayList<>(sampler.mods());
        if (!config.showGroups) {
            result.removeIf(ModMetrics::pseudo);
        }
        result.sort(ModIndex.comparator(sortColumn, sortDescending));
        return result;
    }

    private List<DisplayRow> buildRows() {
        updateGameStats();
        List<DisplayRow> rows = new ArrayList<>();
        ModMetrics gameMod = null;
        for (ModMetrics mod : sortedMods()) {
            if ("game".equals(mod.id())) {
                gameMod = mod;
                break;
            }
        }
        rows.add(new DisplayRow(Component.translatable("screen.modresourcemanager.group.game").getString(), gameMod, 0, true));
        if (gameDetailsExpanded) {
            int total = Math.max(1, gameStats.players() + gameStats.living() + gameStats.items() + gameStats.projectiles() + gameStats.redstone());
            rows.add(childRow("screen.modresourcemanager.game.players", gameStats.players(), gameMod, total, 14));
            rows.add(childRow("screen.modresourcemanager.game.mobs", gameStats.living(), gameMod, total, 14));
            rows.add(childRow("screen.modresourcemanager.game.items", gameStats.items(), gameMod, total, 14));
            rows.add(childRow("screen.modresourcemanager.game.projectiles", gameStats.projectiles(), gameMod, total, 14));
            rows.add(childRow("screen.modresourcemanager.game.redstone", gameStats.redstone(), gameMod, total, 14));
        }
        for (ModMetrics mod : sortedMods()) {
            if (!"game".equals(mod.id())) {
                rows.add(new DisplayRow(null, mod, 0, false));
            }
        }
        return rows;
    }

    private DisplayRow childRow(String key, int count, ModMetrics gameMod, int total, int indent) {
        String text = Component.translatable(key).getString();
        double share = total > 0 ? (double) count / total : 0.0;
        double cpu = gameMod == null ? 0.0 : gameMod.cpuPercent() * share;
        long alloc = gameMod == null ? 0L : Math.round(gameMod.allocatedBytesPerSecond() * share);
        ModMetrics child = new ModMetrics(
                "game.child." + key,
                text,
                "",
                List.of(),
                "",
                true,
                -1L,
                count
        );
        child.updateDynamic(cpu, alloc, 0);
        return new DisplayRow(null, child, indent, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean flag) {
        if (event.button() == 0 && selectedTab == 0) {
            DisplayRow gameHeader = rowAt(event.x(), event.y());
            if (gameHeader != null && gameHeader.gameHeader()) {
                gameDetailsExpanded = !gameDetailsExpanded;
                return true;
            }
            if (isOverScrollbar(event.x(), event.y())) {
                draggingScrollbar = true;
                scrollDragStartY = (int) event.y();
                scrollDragStartOffset = scrollOffset;
                return true;
            }
            int headerY = top() + HEADER_Y_OFFSET;
            if (event.y() >= headerY && event.y() < headerY + ROW_HEIGHT) {
                String[] columns = {"name", "cpu", "memory", "disk", "classes"};
                for (int i = 0; i < columns.length; i++) {
                    int columnLeft = left() + COLUMN_X_OFFSETS[i];
                    int columnRight = i + 1 < COLUMN_X_OFFSETS.length
                            ? left() + COLUMN_X_OFFSETS[i + 1]
                            : left() + contentWidth() - 8;
                    if (event.x() >= columnLeft && event.x() < columnRight) {
                        if (columns[i].equals(sortColumn)) {
                            sortDescending = !sortDescending;
                        } else {
                            sortColumn = columns[i];
                            sortDescending = false;
                        }
                        config.sortColumn = sortColumn;
                        config.sortDescending = sortDescending;
                        try {
                            config.save(ModConfigPath.resolve());
                        } catch (java.io.IOException ignored) {
                            // Keep the in-memory sort even if the config file cannot be written.
                        }
                        return true;
                    }
                }
            }

            ModMetrics clicked = modAt(event.x(), event.y());
            if (clicked != null) {
                long now = System.nanoTime();
                if (clicked == lastClickedMod && now - lastClickedNanos < 250_000_000L) {
                    minecraft.keyboardHandler.setClipboard(clicked.name());
                }
                lastClickedMod = clicked;
                lastClickedNanos = now;
                return true;
            }
        }
        return super.mouseClicked(event, flag);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        if (selectedTab != 0) {
            return false;
        }
        List<DisplayRow> rows = buildRows();
        int maxRows = maxVisibleRows();
        int maxScroll = Math.max(0, rows.size() - maxRows);
        if (maxScroll <= 0) {
            return false;
        }
        int trackLeft = left() + contentWidth() - 8;
        int trackRight = left() + contentWidth() - 1;
        int trackTop = top() + HEADER_Y_OFFSET + 18;
        int trackBottom = trackTop + maxRows * ROW_HEIGHT;
        return mouseX >= trackLeft && mouseX <= trackRight && mouseY >= trackTop && mouseY <= trackBottom;
    }

    private ModMetrics modAt(double mouseX, double mouseY) {
        DisplayRow row = rowAt(mouseX, mouseY);
        return row == null ? null : row.mod();
    }

    private DisplayRow rowAt(double mouseX, double mouseY) {
        if (selectedTab != 0) {
            return null;
        }
        int headerY = top() + HEADER_Y_OFFSET;
        int rowAreaTop = headerY + 18;
        int maxRows = maxVisibleRows();
        int rowAreaBottom = rowAreaTop + maxRows * ROW_HEIGHT;
        if (mouseY < rowAreaTop || mouseY >= rowAreaBottom || mouseX < left() + 8 || mouseX >= left() + contentWidth() - 8) {
            return null;
        }
        List<DisplayRow> rows = buildRows();
        int index = scrollOffset + (int) ((mouseY - rowAreaTop) / ROW_HEIGHT);
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        return rows.get(index);
    }

    private void updateHoveredMod(double mouseX, double mouseY) {
        ModMetrics mod = modAt(mouseX, mouseY);
        if (mod != hoveredMod) {
            hoveredMod = mod;
            hoverStartedNanos = System.nanoTime();
        }
    }

    private void renderHoverTooltip(GuiGraphicsExtractor graphics, ModMetrics mod, int mouseX, int mouseY) {
        int width = Math.min(220, font.width(mod.name()) + 12);
        int height = 14;
        int x = Math.max(2, Math.min(this.width - width - 2, mouseX + 8));
        int y = Math.max(2, Math.min(this.height - height - 2, mouseY - height - 4));
        graphics.fill(x, y, x + width, y + height, 0xE0000000);
        graphics.text(font, mod.name(), x + 5, y + 3, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (selectedTab == 0) {
            List<DisplayRow> rows = buildRows();
            int maxRows = maxVisibleRows();
            int maxScroll = Math.max(0, rows.size() - maxRows);
            if (maxScroll > 0) {
                scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(verticalAmount)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            List<DisplayRow> rows = buildRows();
            int maxRows = maxVisibleRows();
            int maxScroll = Math.max(0, rows.size() - maxRows);
            int trackTop = top() + HEADER_Y_OFFSET + 18;
            int trackHeight = maxRows * ROW_HEIGHT;
            if (maxScroll > 0 && trackHeight > 0) {
                int thumbHeight = Math.max(12, trackHeight * maxRows / rows.size());
                int usable = trackHeight - thumbHeight;
                int delta = (int) event.y() - scrollDragStartY;
                int newOffset = scrollDragStartOffset + (usable > 0 ? delta * maxScroll / usable : 0);
                scrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_BACKSLASH) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void renderPlayerInfo(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        int panelX = left + contentWidth - 190;
        int panelY = top + 48;
        int panelWidth = 178;
        int panelHeight = 100;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0202020);

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(String.format(java.util.Locale.ROOT, "XYZ %.1f / %.1f / %.1f", minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()));
        lines.add("Facing " + minecraft.player.getDirection().getName());
        lines.add("Dimension " + minecraft.level.dimension().toString());
        lines.add("FPS " + minecraft.getFps());
        lines.add("Entities " + minecraft.level.getEntityCount());
        lines.add("Players " + minecraft.level.players().size());

        int y = panelY + 7;
        for (String line : lines) {
            graphics.text(font, line, panelX + 7, y, 0xFFFFFFFF);
            y += 13;
        }
    }

    private void updateGameStats() {
        if (minecraft.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastGameStatsNanos < 1_000_000_000L) {
            return;
        }
        lastGameStatsNanos = now;

        int players = minecraft.level.players().size();
        int living = 0;
        int items = 0;
        int projectiles = 0;
        int other = 0;
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof ItemEntity) {
                items++;
            } else if (entity instanceof Projectile) {
                projectiles++;
            } else if (entity instanceof net.minecraft.world.entity.LivingEntity) {
                living++;
            } else {
                other++;
            }
        }

        int redstone = 0;
        for (BlockEntity blockEntity : minecraft.level.getGloballyRenderedBlockEntities()) {
            String type = blockEntity.getType().toString().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("redstone") || type.contains("comparator") || type.contains("repeater")) {
                redstone++;
            }
        }

        gameStats = new GameEntityStats(players, living, items, projectiles, redstone, minecraft.level.getEntityCount());
    }

    private void renderGameBreakdown(GuiGraphicsExtractor graphics, int left, int top, int contentWidth, int contentHeight) {
        int panelTop = top + contentHeight - 82;
        int panelHeight = 72;
        graphics.fill(left + 6, panelTop, left + contentWidth - 6, panelTop + panelHeight, 0xD0181818);
        graphics.fill(left + 6, panelTop, left + contentWidth - 6, panelTop + 1, 0xFF5A5A5A);
        graphics.fill(left + 6, panelTop + panelHeight - 1, left + contentWidth - 6, panelTop + panelHeight, 0xFF5A5A5A);

        String header = (gameDetailsExpanded ? "▼ " : "▶ ") + "Minecraft / Game";
        graphics.text(font, header, left + 12, panelTop + 5, 0xFF4FC3F7);

        if (!gameDetailsExpanded) {
            return;
        }

        String[] lines = {
                "Players: " + gameStats.players(),
                "Mobs: " + gameStats.living(),
                "Items: " + gameStats.items(),
                "Projectiles: " + gameStats.projectiles(),
                "Redstone BE: " + gameStats.redstone(),
                "Total Entities: " + gameStats.totalEntities()
        };
        int x = left + 14;
        int y = panelTop + 20;
        for (int i = 0; i < lines.length; i++) {
            graphics.text(font, lines[i], x + (i % 2 == 0 ? 0 : 130), y + (i / 2) * 15, 0xFFDDDDDD);
        }
    }

    private int left() {
        return (width - contentWidth()) / 2;
    }

    private int top() {
        return (height - contentHeight()) / 2;
    }

    private int contentWidth() {
        return Math.min(width - 8, MAX_CONTENT_WIDTH);
    }

    private int contentHeight() {
        return Math.min(height - 8, MAX_CONTENT_HEIGHT);
    }

    private int maxVisibleRows() {
        return Math.max(1, (contentHeight() - HEADER_Y_OFFSET - 40) / ROW_HEIGHT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "?";
        }
        return value.length() <= maxLength ? value : value.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private String formatGpu(GpuReading gpu) {
        if (!gpu.available()) {
            return Component.translatable("screen.modresourcemanager.gpu.unavailable").getString();
        }
        if (gpu.memoryUsedBytes() >= 0 && gpu.memoryTotalBytes() > 0) {
            return String.format(
                    java.util.Locale.ROOT,
                    "%.1f%% (%s/%s)",
                    gpu.utilizationPercent(),
                    ByteUnits.format(gpu.memoryUsedBytes()),
                    ByteUnits.format(gpu.memoryTotalBytes())
            );
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", gpu.utilizationPercent());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int color(String hex) {
        if (hex == null || hex.isEmpty()) {
            return 0xFF4FC3F7;
        }
        String normalized = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            long rgb = Long.parseLong(normalized, 16);
            return 0xFF000000 | (int) (rgb & 0xFFFFFFL);
        } catch (NumberFormatException ignored) {
            return 0xFF4FC3F7;
        }
    }

    private static final class ModConfigPath {
        private ModConfigPath() {
        }

        private static java.nio.file.Path resolve() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("modresourcemanager.json");
        }
    }

    private record GameEntityStats(int players, int living, int items, int projectiles, int redstone, int totalEntities) {
        private static final GameEntityStats EMPTY = new GameEntityStats(0, 0, 0, 0, 0, 0);
    }

    private record DisplayRow(String text, ModMetrics mod, int indent, boolean gameHeader) {
    }
}
