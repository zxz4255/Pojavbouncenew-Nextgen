package liquidbounce.DroidBridge.mixin;

import net.ccbluex.liquidbounce.integration.screen.CustomScreenType;
import net.ccbluex.liquidbounce.integration.screen.impl.CustomSharedMinecraftScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes escape do nothing on the client's title screen.
 *
 * The title screen is a {@link CustomSharedMinecraftScreen} that wraps the vanilla one, and escape
 * runs its {@code onClose}: the virtual screen is closed, the theme is sent to {@code /none} and the
 * client opens the title screen again on the next tick. The page is a single page app, so the whole
 * title unmounts and mounts again, which replays the fly-in of every button - that is the animation
 * reload that escape caused.
 *
 * Swallowing the key before {@link Screen#keyPressed} looks at it keeps the screen, the virtual
 * screen and the page exactly as they are. Every other screen keeps the vanilla behaviour.
 */
@Mixin(Screen.class)
public class MixinScreenEscape {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void droidbridge$keepTitleScreen(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event == null || !event.isEscape()) {
            return;
        }

        if (!(((Object) this) instanceof CustomSharedMinecraftScreen screen)) {
            return;
        }

        if (screen.getScreenType() != CustomScreenType.TITLE) {
            return;
        }

        cir.setReturnValue(true);
    }

}