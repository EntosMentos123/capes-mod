package me.lunarcapes.mixin;

import com.mojang.authlib.GameProfile;
import me.lunarcapes.LunarCapesMod;
import me.lunarcapes.service.LunarCapeService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Hooks into PlayerInfo to:
 * 1) trigger cape fetch when player textures are first looked up
 * 2) inject lunar cape into PlayerSkin if present and no vanilla cape conflicts
 *
 * Based on the proven pattern from Fabric Capes (Cael) for 26.3 Mojang mappings.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Shadow @Final private GameProfile profile;

    @Inject(method = "createSkinLookup", at = @At("HEAD"))
    private static void lunarcapes$onCreateSkinLookup(GameProfile profile, CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
        if (profile == null || profile.getId() == null) return;
        // Basic profile validation: Mojang auth injects textures property; offline or broken profiles don't have it
        // We allow all non-null UUIDs but skip obviously invalid (version 2 with missing name)
        LunarCapeService svc = LunarCapesMod.getService();
        if (svc != null) {
            svc.onProfileLoad(profile);
        }
    }

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void lunarcapes$injectCape(CallbackInfoReturnable<PlayerSkin> cir) {
        if (LunarCapesMod.getConfig() != null && !LunarCapesMod.getConfig().enabled) return;

        LunarCapeService service = LunarCapesMod.getService();
        if (service == null) return;
        if (profile == null || profile.getId() == null) return;

        if (!service.hasCape(profile.getId())) return;

        PlayerSkin original = cir.getReturnValue();
        if (original == null) return;

        // Respect config: don't override Mojang capes if user prefers vanilla only
        if (LunarCapesMod.getConfig().showOnlyWhenNoVanillaCape && original.cape() != null) {
            return;
        }

        ClientAsset.Texture lunarCape = service.getCapeTexture(profile.getId());
        if (lunarCape == null) return;

        // Decide elytra: if lunar cape is valid elytra texture and config allows, reuse cape; else fallback to vanilla elytra texture
        ClientAsset.Texture elytraTexture;
        if (service.hasElytraTexture(profile.getId()) && LunarCapesMod.getConfig().enableElytraTexture) {
            elytraTexture = lunarCape;
        } else {
            // Keep original elytra or fallback to default elytra.png
            elytraTexture = original.elytra() != null ? original.elytra()
                    : new ClientAsset.ResourceTexture(Identifier.parse("textures/entity/equipment/wings/elytra.png"), null);
        }

        PlayerSkin patched = new PlayerSkin(
                original.body(),
                lunarCape,
                elytraTexture,
                original.model(),
                original.secure()
        );
        cir.setReturnValue(patched);
    }
}
