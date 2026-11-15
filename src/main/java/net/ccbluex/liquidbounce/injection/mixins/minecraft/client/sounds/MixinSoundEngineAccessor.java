package net.ccbluex.liquidbounce.injection.mixins.minecraft.client.sounds;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface MixinSoundEngineAccessor {
    @Accessor("channelAccess") ChannelAccess liquidbounce$getChannelAccess();
}
