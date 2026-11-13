/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleScreenshotESP;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractorScreenshotEsp {
    @ModifyExpressionValue(method = "isEntityVisible", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
    private boolean liquid_bounce$includeSelectedOccludedSections(boolean original, @Local(argsOnly = true) Entity entity) {
        var esp = ModuleScreenshotESP.INSTANCE;
        return original || (esp.getThroughWalls() && esp.wants(entity));
    }
}
