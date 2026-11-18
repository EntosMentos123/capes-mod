package me.lunarcapes;

import me.lunarcapes.config.LunarCapesConfig;
import me.lunarcapes.service.LunarCapeService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LunarCapesMod implements ClientModInitializer {
    public static final String MOD_ID = "lunarcapes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LunarCapesConfig config;
    private static LunarCapeService service;

    public static LunarCapesConfig getConfig() {
        if (config == null) config = LunarCapesConfig.loadOrCreate();
        return config;
    }

    public static LunarCapeService getService() {
        return service;
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[LunarCapes] Initializing for Minecraft 26.3 — Lunar Client capes without Lunar Client!");
        config = LunarCapesConfig.loadOrCreate();
        service = new LunarCapeService(config);

        // Register /lunarcapes command for debugging
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("lunarcapes")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("[LunarCapes] Config:\n" + getConfig().toDebugString()), false);
                    return 1;
                })
                .then(ClientCommandManager.literal("reload").executes(ctx -> {
                    config = LunarCapesConfig.loadOrCreate();
                    if (service != null) service.updateConfig(config);
                    ctx.getSource().sendSuccess(() -> Component.literal("[LunarCapes] Config reloaded!").withStyle(ChatFormatting.GREEN), false);
                    return 1;
                }))
                .then(ClientCommandManager.literal("clearcache").executes(ctx -> {
                    if (service != null) {
                        int cleared = service.clearCache();
                        ctx.getSource().sendSuccess(() -> Component.literal("[LunarCapes] Cleared " + cleared + " cached capes.").withStyle(ChatFormatting.YELLOW), false);
                    }
                    return 1;
                }))
            );
            dispatcher.register(ClientCommandManager.literal("lcapes")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("[LunarCapes] Use /lunarcapes (reload | clearcache)"), false);
                    return 1;
                }));
        });

        LOGGER.info("[LunarCapes] Ready! Lunar cape provider active: {}", config.primaryApiUrl);
    }
}
