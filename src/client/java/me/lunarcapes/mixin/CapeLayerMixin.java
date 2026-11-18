package me.lunarcapes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fix transparency for custom capes.
 * Vanilla capes use armorCutoutNoCull for proper translucent rendering;
 * some custom cape paths would otherwise use entitySolid which discards alpha.
 * Wrapping ensures lunar capes render with correct transparency like vanilla.
 */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {
    @WrapOperation(method = "submit*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType lunarcapes$fixTransparency(Identifier texture, Operation<RenderType> original) {
        return RenderTypes.armorCutoutNoCull(texture);
    }
}
