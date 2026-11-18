# LunarCapes

Fabric mod for Minecraft 26.3 that shows Lunar Client cloaks on regular Fabric.
No Lunar install needed.

I made this because most of my friends use Lunar and I was on Fabric. I wanted to see their actual cloaks without switching launchers, so this mod fetches the same textures Lunar uses.

## What it does

When someone joins with a Lunar cloak, the mod downloads the image and replaces their cape texture. It works with the original Lunar cloaks, not a custom replacement. If they do not have one, nothing changes.

Vanilla capes still work. There is a config option to keep vanilla capes when present if you prefer that.

## Requires

- Minecraft Java 26.3 only
- Fabric Loader 0.20.1 or newer
- Fabric API 0.166.2+26.3
- Java 25

This will not load on other versions. The mod is pinned to `minecraft: 26.3` in `fabric.mod.json` and built with Loom 1.19 on official Mojang mappings for 26.3. If you need a backport you have to change `minecraft_version` and rebuild.

## How it works

The cape handling in 26.3 is through `PlayerInfo` and `PlayerSkin`. The mod injects at two points:

- on `createSkinLookup` it starts an async fetch for that UUID
- on `getSkin` it swaps in the downloaded texture if one was found

The download part tries the URLs from the config in order. Some endpoints return a PNG directly, others return JSON with a URL inside. The mod handles both and checks a few common JSON shapes like `url`, `capeUrl`, `cloak.texture` and the Mojang profile format.

Once downloaded the image is read with `NativeImage`, padded to a power of two if needed (at least 64x32), and registered as a `DynamicTexture` under `lunarcapes:<uuid>`. If the cape is 64x64 and `enableElytraTexture` is on, it is also used for the elytra.

Capes can have transparency issues in vanilla. The cape layer mixin switches `entitySolid` to `armorCutoutNoCull` so alpha renders right.

The pattern is similar to Cael's Capes, adapted for the new mappings in 26.3. Apollo docs are at https://lunarclient.dev/apollo/introduction.

## Config

A file is created at `config/lunarcapes.json` on first start. Edit it and run `/lunarcapes reload` in game. No restart needed.

Default endpoints:

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

Placeholders you can use are `{uuid}`, `{uuid_nodash}` and `{name}`.

Lunar's official hosts usually need auth from the Apollo websocket, so they often return 403 unless proxied. Community mirrors like lunar.zeratos.com cache the same data without auth. If Lunar changes the CDN, just update the URL in the config.

Other options in the file:

```json
{
  "enabled": true,
  "enableElytraTexture": true,
  "enableAnimatedCapes": false,
  "showOnlyWhenNoVanillaCape": false,
  "cacheTtlMinutes": 60,
  "httpTimeoutSeconds": 7,
  "verboseLogging": false
}
```

Set `verboseLogging` to true if you want to see which URL worked in the log.

## Install

1. Install Fabric for 26.3 (Fabric installer or Prism)
2. Put `fabric-api-0.166.2+26.3.jar` and the built `lunarcapes-1.0.0.jar` into `mods/`
3. Launch with Java 25 and join a server. If someone with a Lunar cloak is online you should see it right away.

## Commands

`/lunarcapes` shows the current config
`/lunarcapes reload` reloads the json file
`/lunarcapes clearcache` clears the downloaded capes

There is also `/lcapes` as a short alias.

## Build

You need Java 25.

```
./gradlew build
```

The jar ends up in `build/libs/`. The project was started from the Fabric example mod for 26.3.

## Common issues

No capes showing: The first URL in the config is the official Lunar endpoint and it usually needs auth. Try moving a working mirror to the top and reload with `/lunarcapes reload`.

Crash on startup: Check you are actually on 26.3. This build does not run on 1.21 or other 26.x snapshots.

Elytra looks like a cape: This is on purpose when the cape is 64x64. Turn off `enableElytraTexture` if you do not want it.

## Credits

Lunar Client and Moonsworth for the original cloak system. Docs at https://lunarclient.dev and https://github.com/LunarClient/Apollo.
Cael's Capes for the Fabric injection approach.
FabricMC for the example mod and Loom.

MIT License - see LICENSE.
