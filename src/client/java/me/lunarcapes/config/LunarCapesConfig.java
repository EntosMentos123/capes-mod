package me.lunarcapes.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for LunarCapes. Stored as JSON5 in config/lunarcapes.json
 * Allows customizing the API endpoint in case Lunar changes their CDN.
 */
public class LunarCapesConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("lunarcapes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // === Primary lunar endpoint ===
    // Lunar's official cloak delivery is via authenticated Apollo websocket (wss://assetserver.lunarclientprod.com)
    // and CDN (https://textures.lunarclientcdn.com) documented at https://lunarclient.dev/apollo/introduction
    // There is no public unauthenticated REST for cloaks, so this mod proxies through community mirrors
    // that cache Apollo data. See README for details. Override with any URL returning PNG or JSON {url, texture}.
    // Placeholders: {uuid} (dashed), {uuid_nodash}, {name}
    public String primaryApiUrl = "https://api.lunarclientprod.com/cloak/{uuid}";

    // Fallback APIs tried in order if primary fails. Set to empty to disable.
    public String[] fallbackApiUrls = new String[]{
            "https://textures.lunarclientcdn.com/capes/{uuid}.png",
            "https://lunar.zeratos.com/api/capes/{uuid}",
            // Mantle-style mirror (many lunar capes are mirrored there)
            "https://api.mantle.gg/v1/capes/lunar/{uuid}",
            // Generic capes.dev aggregator (supports lunar if mirrored)
            "https://api.capes.dev/lunar/{uuid}"
    };

    // Behaviour toggles
    public boolean enabled = true;
    public boolean enableElytraTexture = true;
    public boolean enableAnimatedCapes = false; // reserved for future animated cloaks
    public boolean showOnlyWhenNoVanillaCape = false; // if true, don't override mojang capes
    public int cacheTtlMinutes = 60;
    public int httpTimeoutSeconds = 7;

    // Debug
    public boolean verboseLogging = false;

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("lunarcapes.json");
    }

    public static LunarCapesConfig loadOrCreate() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                LunarCapesConfig cfg = GSON.fromJson(json, LunarCapesConfig.class);
                if (cfg != null) {
                    // migration: fill missing fields
                    if (cfg.primaryApiUrl == null) cfg.primaryApiUrl = new LunarCapesConfig().primaryApiUrl;
                    if (cfg.fallbackApiUrls == null) cfg.fallbackApiUrls = new LunarCapesConfig().fallbackApiUrls;
                    cfg.save(); // re-save to add new keys
                    LOGGER.info("[LunarCapes] Loaded config from {}", path);
                    return cfg;
                }
            } catch (IOException e) {
                LOGGER.warn("[LunarCapes] Failed to read config, using defaults", e);
            }
        }
        LunarCapesConfig def = new LunarCapesConfig();
        def.save();
        LOGGER.info("[LunarCapes] Created default config at {}", path);
        return def;
    }

    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.error("[LunarCapes] Failed to save config", e);
        }
    }

    public String toDebugString() {
        return "enabled=" + enabled + "\nprimaryApiUrl=" + primaryApiUrl + "\nfallbacks=" + String.join(", ", fallbackApiUrls)
                + "\ncacheTtlMinutes=" + cacheTtlMinutes + "\nverbose=" + verboseLogging;
    }

    public String resolveUrl(String template, String uuidWithDashes, String uuidNoDash, String name) {
        return template.replace("{uuid}", uuidWithDashes)
                .replace("{uuid_nodash}", uuidNoDash)
                .replace("{uuidNoDash}", uuidNoDash)
                .replace("{name}", name != null ? name : "")
                .replace("{id}", uuidWithDashes);
    }
}
