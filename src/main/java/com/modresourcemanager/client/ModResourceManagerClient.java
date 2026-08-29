package com.modresourcemanager.client;

import com.modresourcemanager.core.MetricsSampler;
import com.modresourcemanager.core.ModConfig;
import com.modresourcemanager.core.ModIndex;
import com.modresourcemanager.gui.ResourceManagerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public final class ModResourceManagerClient implements ClientModInitializer {
    private final ModIndex index = new ModIndex();
    private final MetricsSampler sampler = new MetricsSampler(index);
    private ModConfig config;
    private KeyMapping openKey;
    private boolean managerOpen;
    private boolean chineseLanguageNormalized;

    @Override
    public void onInitializeClient() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("modresourcemanager.json");
        config = ModConfig.load(configPath);
        sampler.start(config);
        sampler.setMonitoringEnabled(false);

        registerKeyBinding();
        registerCommand();
        normalizeChineseLanguage();
    }

    private void normalizeChineseLanguage() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (chineseLanguageNormalized) {
                return;
            }
            String language = client.getLanguageManager().getSelected();
            if (language != null && language.startsWith("zh") && !"zh_cn".equals(language)) {
                client.getLanguageManager().setSelected("zh_cn");
                client.options.languageCode = "zh_cn";
            }
            chineseLanguageNormalized = true;
        });
    }

    private void registerKeyBinding() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.modresourcemanager.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,
                new KeyMapping.Category(Identifier.fromNamespaceAndPath("modresourcemanager", "main"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                toggleScreen(client);
            }
        });
    }

    private void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("modresources").executes(context -> {
                    Minecraft.getInstance().execute(() -> {
                        sampler.setMonitoringEnabled(true);
                        managerOpen = true;
                        openScreen(Minecraft.getInstance(), new ResourceManagerScreen(sampler, config, () -> {
                            managerOpen = false;
                            sampler.setMonitoringEnabled(false);
                        }));
                    });
                    return 1;
                }))
        );
    }

    private void toggleScreen(Minecraft client) {
        if (managerOpen) {
            managerOpen = false;
            closeScreen(client);
            sampler.setMonitoringEnabled(false);
        } else {
            sampler.setMonitoringEnabled(true);
            managerOpen = true;
            openScreen(client, new ResourceManagerScreen(sampler, config, () -> {
                managerOpen = false;
                sampler.setMonitoringEnabled(false);
            }));
        }
    }

    private static void openScreen(Minecraft client, Screen screen) {
        try {
            client.getClass().getMethod("setScreen", Screen.class).invoke(client, screen);
            return;
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the newer 26.2+ method name.
        }
        try {
            client.getClass().getMethod("setScreenAndShow", Screen.class).invoke(client, screen);
        } catch (ReflectiveOperationException ignored) {
            // Neither API is available; do not crash the client.
        }
    }

    private static void closeScreen(Minecraft client) {
        try {
            client.getClass().getMethod("setScreen", Screen.class).invoke(client, (Screen) null);
            return;
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the newer 26.2+ method name.
        }
        try {
            client.getClass().getMethod("setScreenAndShow", Screen.class).invoke(client, (Screen) null);
        } catch (ReflectiveOperationException ignored) {
            // Neither API is available; do not crash the client.
        }
    }
}
