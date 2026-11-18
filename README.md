# LunarCapes — See Lunar Client Capes on Fabric 26.3

**For Minecraft Java 26.3 only.** Client-side Fabric mod that lets you see [Lunar Client](https://lunarclient.dev/) cloaks/cosmetics while playing on normal Fabric — no Lunar Client required.

> Uses public mirrors of Lunar's Apollo system. Lunar delivers cloaks over an authenticated Apollo websocket (`wss://assetserver.lunarclientprod.com`) and CDN (`textures.lunarclientcdn.com`). This mod fetches the same PNGs through community mirrors that proxy Apollo data, so you see the *original* Lunar capes (Dragon, Galaxy, etc.), not a custom moon icon.

## Why 26.3 only?

- Built against Mojang official mappings for **26.3** (Mojang version scheme `year.drop` — 26.3 is Java Edition's third 2026 drop).
- `fabric.mod.json` declares `"minecraft": "26.3"` strictly, Fabric Loader will refuse to load on any other version.
- Toolchain: **Fabric Loom 1.19-SNAPSHOT, Fabric Loader 0.20.1, Fabric API 0.166.2+26.3, Java 25**.

If you need 1.21.x, change `minecraft_version` in `gradle.properties` and `fabric.mod.json` accordingly.

## How it works

1. **Mixin on `PlayerInfo`**:
   - `@Inject(createSkinLookup)` triggers an async fetch when a player's skin is first resolved.
   - `@Inject(getSkin)` replaces the `PlayerSkin` cape texture with the fetched Lunar texture if present. Respects vanilla capes if `showOnlyWhenNoVanillaCape=true`.

2. **`LunarCapeService`**:
   - Tries `primaryApiUrl` first, then `fallbackApiUrls` in order.
   - Endpoints may return raw `image/png` or JSON (`{url, capeUrl, texture, ...}`) — both handled. Nested fields (`cloak.texture`, `data.url`, Mojang `textures.CAPE.url`) supported.
   - Downloads PNG → `NativeImage` → scales to power-of-two (64×32 minimum) → registers as `DynamicTexture` with `Identifier("lunarcapes:<uuid_nodash>")`.
   - Reuses cape as elytra when `enableElytraTexture=true` and image is elytra-compatible (64×64).

3. **Transparency fix**:
   - `CapeLayerMixin` wraps `RenderTypes.entitySolid` → `RenderTypes.armorCutoutNoCull` so alpha renders correctly.

Reference implementation adapted from [Cael's Capes](https://github.com/CaelTheColher/Capes) (26.3 branch) + Apollo docs at https://lunarclient.dev/apollo/introduction and https://lunarclient.dev/apollo/developers/private-modules/cosmetic .

## Default API endpoints

You can change these in `config/lunarcapes.json` without recompiling:

```json
{
  "primaryApiUrl": "https://api.lunarclientprod.com/cloak/{uuid}",
  "fallbackApiUrls": [
    "https://textures.lunarclientcdn.com/capes/{uuid}.png",
    "https://lunar.zeratos.com/api/capes/{uuid}",
    "https://api.mantle.gg/v1/capes/lunar/{uuid}",
    "https://api.capes.dev/lunar/{uuid}"
  ]
}
```

Placeholders: `{uuid}` (dashed), `{uuid_nodash}`, `{name}`.

> **Note:** `api.lunarclientprod.com` and `textures.lunarclientcdn.com` are Lunar's official hosts but normally require Apollo auth. Public mirrors like `lunar.zeratos.com` cache the same data unauthenticated. If Lunar changes their CDN, just update the template in the config.

## Installation (26.3)

1. Install **Fabric Loader 0.20.1** for Minecraft **26.3** (use Fabric installer or Prism Launcher).
2. Put `fabric-api-0.166.2+26.3.jar` + `lunarcapes-1.0.0.jar` in `mods/`.
3. Launch with **Java 25** (26.3 requires Java 25).
4. Join any server; Lunar capes of other players appear automatically if they own one on Lunar.

## Commands

- `/lunarcapes` — shows current config
- `/lunarcapes reload` — reloads `config/lunarcapes.json`
- `/lunarcapes clearcache` — clears cached cape textures
- Alias: `/lcapes`

## Configuration (`config/lunarcapes.json`)

Created on first run:

```json
{
  "primaryApiUrl": "https://api.lunarclientprod.com/cloak/{uuid}",
  "fallbackApiUrls": [ ... ],
  "enabled": true,
  "enableElytraTexture": true,
  "enableAnimatedCapes": false,
  "showOnlyWhenNoVanillaCape": false,
  "cacheTtlMinutes": 60,
  "httpTimeoutSeconds": 7,
  "verboseLogging": false
}
```

Toggle `verboseLogging` to debug which URL succeeded.

## Building

Requires **Java 25** and Gradle 9.8+:

```bash
./gradlew build
# jar → build/libs/lunarcapes-1.0.0.jar
```

Project is a direct port of `FabricMC/fabric-example-mod#26.3`:

- `minecraft_version=26.3`, `loader_version=0.20.1`, `loom_version=1.19-SNAPSHOT`
- `loom { splitEnvironmentSourceSets() }` etc.

## Troubleshooting

- **No capes visible**: Lunar's official API is auth-walled; try switching `primaryApiUrl` to a working mirror in the config and `/lunarcapes reload`.
- **Crash on startup**: Wrong Minecraft version — this build loads *only* on 26.3. Check `mods/fabric.mod.json` dependency.
- **Elytra shows cape texture**: Disable `enableElytraTexture` if you prefer vanilla elytra.

## Credits

- Lunar Client / Moonsworth — original cloak system & Apollo API (https://lunarclient.dev, https://github.com/LunarClient/Apollo)
- CaelTheColher/Capes — cape injection pattern for Fabric 26.x
- FabricMC — example mod template for 26.3

License: MIT
