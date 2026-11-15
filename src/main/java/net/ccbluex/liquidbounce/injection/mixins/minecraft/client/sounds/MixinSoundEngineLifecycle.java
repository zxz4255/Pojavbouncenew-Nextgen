package net.ccbluex.liquidbounce.injection.mixins.minecraft.client.sounds;

import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicSoundLifecycle;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineLifecycle {
    @Inject(method = "resume", at = @At("TAIL"))
    private void liquidbounce$musicAfterResume(CallbackInfo ci) {
        MusicSoundLifecycle.afterResume();
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void liquidbounce$musicBeforeStopAll(CallbackInfo ci) {
        MusicSoundLifecycle.beforeStopAll();
    }
}
