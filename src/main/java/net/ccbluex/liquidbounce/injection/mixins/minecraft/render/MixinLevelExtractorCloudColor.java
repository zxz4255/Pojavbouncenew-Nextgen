/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ccbluex.liquidbounce.features.module.modules.render.customambience.ModuleCustomAmbience;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractorCloudColor {

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;cloudColor:I",
            opcode = Opcodes.PUTFIELD
        )
    )
    private void liquid_bounce$applyCloudColor(LevelRenderState renderState, int originalColor, Operation<Void> original) {
        var customCloudColor = ModuleCustomAmbience.CloudColor.INSTANCE;
        if (!customCloudColor.getRunning()) {
            original.call(renderState, originalColor);
            return;
        }

        int selectedColor = customCloudColor.getColor().argb();
        int environmentOpacity = originalColor >>> 24;
        int selectedOpacity = selectedColor >>> 24;
        int combinedOpacity = (environmentOpacity * selectedOpacity + 127) / 255;
        int recolored = (combinedOpacity << 24) | (selectedColor & 0x00ffffff);
        original.call(renderState, recolored);
    }
}
