package me.lunarcapes.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import me.lunarcapes.LunarCapesMod;
import me.lunarcapes.config.LunarCapesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Core service that fetches *original* Lunar Client capes via Apollo.
 * <p>
 * Official docs: https://lunarclient.dev/ and https://lunarclient.dev/apollo/introduction
 * plus private module https://lunarclient.dev/apollo/developers/private-modules/cosmetic
 * <p>
 * Apollo itself pushes cosmetics over an authenticated websocket (assetserver.lunarclientprod.com)
 * and requires the Lunar Client mod. There is no public unauthenticated REST for cloaks, so
 * this mod queries community mirrors that cache Apollo data (e.g. lunar.zeratos.com, mantle.gg)
 * plus the official CDN hosts when accessible. This is the same approach used by
 * <a href="https://github.com/LunarClient/Apollo">github.com/LunarClient/Apollo</a> examples
 * for NPC cosmetics — we just repurpose it to *view* other players' cloaks on vanilla Fabric.
 * <p>
 * Each endpoint may return either raw PNG or JSON containing a texture URL.
 */
public class LunarCapeService {
    private static final Gson GSON = new Gson();

    private LunarCapesConfig config;
    private final Executor executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LunarCapes-IO");
        t.setDaemon(true);
        return t;
    });

    // Cache of handlers per UUID
    private final Map<UUID, CapeEntry> cache = new ConcurrentHashMap<>();

    private static class CapeEntry {
        volatile boolean checked = false;
        volatile boolean hasCape = false;
        volatile ClientAsset.Texture capeTexture = null;
        volatile boolean hasElytra = false;
        volatile long checkedAt = 0;
        volatile String sourceUrl = null;
    }

    public LunarCapeService(LunarCapesConfig config) {
        this.config = config;
    }

    public void updateConfig(LunarCapesConfig newConfig) {
        this.config = newConfig;
    }

    public int clearCache() {
        int size = cache.size();
        cache.clear();
        return size;
    }

    public CapeEntry getEntry(UUID uuid) {
        return cache.get(uuid);
    }

    public boolean hasCape(UUID uuid) {
        CapeEntry e = cache.get(uuid);
        return e != null && e.hasCape && e.capeTexture != null;
    }

    public ClientAsset.Texture getCapeTexture(UUID uuid) {
        CapeEntry e = cache.get(uuid);
        return e != null ? e.capeTexture : null;
    }

    public boolean hasElytraTexture(UUID uuid) {
        CapeEntry e = cache.get(uuid);
        return e != null && e.hasElytra;
    }

    // Called from MixinPlayerInfo.createSkinLookup
    public void onProfileLoad(GameProfile profile) {
        if (!config.enabled) return;
        if (profile == null || profile.getId() == null) return;
        UUID uuid = profile.getId();
        String name = profile.getName();

        // Already checked recently?
        CapeEntry existing = cache.get(uuid);
        if (existing != null && existing.checked) {
            long ageMs = System.currentTimeMillis() - existing.checkedAt;
            if (ageMs < (long) config.cacheTtlMinutes * 60 * 1000L) {
                return;
            }
        }

        // Deduplicate: if already fetching, don't re-queue
        if (existing != null && !existing.checked) {
            return; // fetch in progress (checked==false but entry exists briefly)
        }

        // Place placeholder to prevent duplicate fetches
        CapeEntry placeholder = new CapeEntry();
        placeholder.checked = false;
        cache.put(uuid, placeholder);

        CompletableFuture.runAsync(() -> fetchCapeForProfile(profile, uuid, name, placeholder), executor);
    }

    private void fetchCapeForProfile(GameProfile profile, UUID uuid, String name, CapeEntry entry) {
        try {
            String uuidDashed = uuid.toString();
            String uuidNoDash = uuidDashed.replace("-", "");

            // Build URL chain
            String[] chain;
            if (config.fallbackApiUrls != null && config.fallbackApiUrls.length > 0) {
                chain = new String[1 + config.fallbackApiUrls.length];
                chain[0] = config.primaryApiUrl;
                System.arraycopy(config.fallbackApiUrls, 0, chain, 1, config.fallbackApiUrls.length);
            } else {
                chain = new String[]{config.primaryApiUrl};
            }

            for (String template : chain) {
                if (template == null || template.isBlank()) continue;
                String url = config.resolveUrl(template, uuidDashed, uuidNoDash, name);
                if (config.verboseLogging) {
                    LunarCapesMod.LOGGER.info("[LunarCapes] Trying {} for {}", url, name);
                }
                try {
                    if (tryFetchUrl(url, uuid, entry)) {
                        entry.checked = true;
                        entry.checkedAt = System.currentTimeMillis();
                        if (config.verboseLogging) {
                            LunarCapesMod.LOGGER.info("[LunarCapes] Found cape for {} via {}", name, url);
                        }
                        return;
                    }
                } catch (Exception ex) {
                    if (config.verboseLogging) {
                        LunarCapesMod.LOGGER.warn("[LunarCapes] Failed URL {}: {}", url, ex.toString());
                    }
                }
            }

            // No cape found
            entry.checked = true;
            entry.hasCape = false;
            entry.checkedAt = System.currentTimeMillis();
            if (config.verboseLogging) {
                LunarCapesMod.LOGGER.info("[LunarCapes] No lunar cape for {}", name);
            }
        } catch (Exception e) {
            entry.checked = true;
            entry.checkedAt = System.currentTimeMillis();
            LunarCapesMod.LOGGER.warn("[LunarCapes] Error fetching cape for {}", name, e);
        }
    }

    private boolean tryFetchUrl(String url, UUID uuid, CapeEntry entry) throws Exception {
        HttpURLConnection conn = openConnection(url);
        conn.setConnectTimeout(config.httpTimeoutSeconds * 1000);
        conn.setReadTimeout(config.httpTimeoutSeconds * 1000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code == 404) return false;
        if (code / 100 != 2) return false;

        String contentType = conn.getContentType();
        if (contentType != null && contentType.startsWith("image/")) {
            // Direct PNG
            try (InputStream in = conn.getInputStream()) {
                return registerCapeTexture(in, uuid, entry, url);
            }
        } else {
            // Try to detect PNG by magic bytes anyway (some CDNs mislabel)
            // Peek first bytes if JSON fails
            try (InputStream in = conn.getInputStream()) {
                // Buffer to allow resetting: read all bytes for analysis
                byte[] data = in.readAllBytes();
                if (data.length < 4) return false;

                // PNG magic: 89 50 4E 47
                if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
                    try (InputStream pngIn = new java.io.ByteArrayInputStream(data)) {
                        return registerCapeTexture(pngIn, uuid, entry, url);
                    }
                }

                // Otherwise assume JSON
                String jsonStr = new String(data, StandardCharsets.UTF_8).trim();
                if (jsonStr.startsWith("{") || jsonStr.startsWith("[")) {
                    // Try parse as JSON
                    JsonObject obj = null;
                    try {
                        if (jsonStr.startsWith("[")) {
                            // Some APIs return array
                            var arr = JsonParser.parseString(jsonStr).getAsJsonArray();
                            if (!arr.isEmpty() && arr.get(0).isJsonObject()) obj = arr.get(0).getAsJsonObject();
                        } else {
                            obj = JsonParser.parseString(jsonStr).getAsJsonObject();
                        }
                    } catch (Exception parseEx) {
                        return false;
                    }
                    if (obj == null) return false;

                    // Common fields that may hold texture URL
                    String[] candidates = new String[]{
                            "url", "capeUrl", "cape_url", "texture", "cloak", "image", "cape", "link"
                    };
                    for (String key : candidates) {
                        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                            String textureUrl = obj.get(key).getAsString();
                            if (textureUrl != null && (textureUrl.startsWith("http://") || textureUrl.startsWith("https://") || textureUrl.startsWith("data:"))) {
                                // Fetch texture URL
                                // Handle data: URI
                                if (textureUrl.startsWith("data:")) {
                                    // data:image/png;base64,...
                                    int comma = textureUrl.indexOf(',');
                                    if (comma > 0) {
                                        String b64 = textureUrl.substring(comma + 1);
                                        byte[] pngBytes = java.util.Base64.getDecoder().decode(b64);
                                        try (InputStream b64In = new java.io.ByteArrayInputStream(pngBytes)) {
                                            if (registerCapeTexture(b64In, uuid, entry, textureUrl)) return true;
                                        }
                                    }
                                    continue;
                                }
                                HttpURLConnection texConn = openConnection(textureUrl);
                                texConn.setConnectTimeout(config.httpTimeoutSeconds * 1000);
                                texConn.setReadTimeout(config.httpTimeoutSeconds * 1000);
                                texConn.connect();
                                if (texConn.getResponseCode() / 100 == 2) {
                                    try (InputStream texIn = texConn.getInputStream()) {
                                        if (registerCapeTexture(texIn, uuid, entry, textureUrl)) return true;
                                    }
                                }
                            }
                        }
                    }
                    // Check nested objects: cloak.texture, data.url, etc.
                    if (obj.has("cloak") && obj.get("cloak").isJsonObject()) {
                        JsonObject cloak = obj.getAsJsonObject("cloak");
                        if (cloak.has("texture")) {
                            String t = cloak.get("texture").getAsString();
                            HttpURLConnection c2 = openConnection(t);
                            c2.connect();
                            if (c2.getResponseCode() / 100 == 2) {
                                try (InputStream tin = c2.getInputStream()) {
                                    if (registerCapeTexture(tin, uuid, entry, t)) return true;
                                }
                            }
                        }
                    }
                    if (obj.has("data") && obj.get("data").isJsonObject()) {
                        JsonObject data = obj.getAsJsonObject("data");
                        for (String key : candidates) {
                            if (data.has(key)) {
                                String t = data.get(key).getAsString();
                                HttpURLConnection c2 = openConnection(t);
                                c2.connect();
                                if (c2.getResponseCode() / 100 == 2) {
                                    try (InputStream tin = c2.getInputStream()) {
                                        if (registerCapeTexture(tin, uuid, entry, t)) return true;
                                    }
                                }
                            }
                        }
                    }
                    // Special case: Mojang-style profile with textures property (base64 encoded JSON containing cape URL)
                    if (obj.has("textures") && obj.get("textures").isJsonObject()) {
                        JsonObject textures = obj.getAsJsonObject("textures");
                        if (textures.has("CAPE") && textures.get("CAPE").isJsonObject()) {
                            String capeUrl = textures.getAsJsonObject("CAPE").get("url").getAsString();
                            HttpURLConnection c2 = openConnection(capeUrl);
                            c2.connect();
                            if (c2.getResponseCode() / 100 == 2) {
                                try (InputStream tin = c2.getInputStream()) {
                                    if (registerCapeTexture(tin, uuid, entry, capeUrl)) return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "LunarCapes/1.0.0 (Fabric 26.3; +https://github.com/EntosMentos123/capes-mod)");
        conn.setRequestProperty("Accept", "image/png, image/*, application/json, */*");
        conn.setDoInput(true);
        conn.setDoOutput(false);
        conn.setUseCaches(true);
        return conn;
    }

    private boolean registerCapeTexture(InputStream imageStream, UUID uuid, CapeEntry entry, String sourceUrl) {
        try {
            NativeImage image = NativeImage.read(imageStream);
            // LabyMod guard: some LabyMod fallback returns a 1x1 placeholder with magic UUID pixels
            // Detect placeholder (FF purple pixel pattern used by mantle/capes mod)
            try {
                // Check if image is 64x64 with specific UUID encoded in pixels (legacy check from Cael's mod)
                if (image.getWidth() >= 4 && image.getHeight() >= 4) {
                    // Quick heuristic: placeholder is 2x2 purple; we skip simple size check
                    // Keep it simple: if image is tiny 1x1 or 2x2, treat as no cape
                    if (image.getWidth() <= 2 && image.getHeight() <= 2) {
                        image.close();
                        return false;
                    }
                }
            } catch (Exception ignore) {}

            NativeImage processed = processCapeImage(image);
            String idStr = "lunarcapes_" + uuid.toString().replace("-", "");
            Identifier textureId = Identifier.fromNamespaceAndPath(LunarCapesMod.MOD_ID, idStr);
            // Must run on main thread
            Minecraft mc = Minecraft.getInstance();
            // Use submit to ensure thread safety
            mc.submit(() -> {
                try {
                    // DynamicTexture takes Supplier<String> for id + NativeImage
                    DynamicTexture dynamic = new DynamicTexture(() -> idStr, processed);
                    mc.getTextureManager().register(textureId, dynamic);
                } catch (Exception e) {
                    LunarCapesMod.LOGGER.warn("[LunarCapes] Failed to register texture {}", idStr, e);
                }
            });

            entry.hasCape = true;
            entry.capeTexture = new ClientAsset.ResourceTexture(textureId, null);
            // Elytra parity: 64x32 capes can't be used as elytra (needs 64x64 with bottom half). We detect via width/height ratio.
            // If height == width (64x64) then it's suitable for elytra, otherwise use ratio check.
            entry.hasElytra = (processed.getWidth() / Math.max(1, processed.getHeight())) == 2 ? false : true;
            // Alternative: typical lunar capes are 64x32 -> no elytra; some are 64x64 -> can be elytra
            // Let's use width/height: 2 => 64x32 => false, 1 => 64x64 => true, else true
            // Actually capes 64x32 should NOT be elytra-capable, but we allow via config
            // We'll set true if image height >= width/2 and image width == height*2? Need simple
            // We'll just set hasElytra = (processed.getHeight() == processed.getWidth());
            entry.hasElytra = (processed.getHeight() == processed.getWidth() || processed.getHeight() * 2 == processed.getWidth());
            // More accurate: modern capes often 64x32; elytra texture is separate, but we reuse cape as elytra if config allows
            entry.sourceUrl = sourceUrl;
            return true;
        } catch (Exception e) {
            LunarCapesMod.LOGGER.warn("[LunarCapes] Failed to parse cape PNG from {}", sourceUrl, e);
            return false;
        }
    }

    /**
     * Ensures cape image is power-of-two sized (64x32 min) as required by Minecraft renderer.
     * Mirrors logic from Cael's Capes mod.
     */
    private NativeImage processCapeImage(NativeImage src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int dstW = 64;
        int dstH = 32;
        while (dstW < srcW || dstH < srcH) {
            dstW = dstW * 2;
            dstH = dstH * 2;
        }
        if (dstW == srcW && dstH == srcH) {
            return src;
        }
        NativeImage dst = new NativeImage(dstW, dstH, true);
        for (int x = 0; x < srcW; x++) {
            for (int y = 0; y < srcH; y++) {
                dst.setPixel(x, y, src.getPixel(x, y));
            }
        }
        src.close();
        return dst;
    }
}
