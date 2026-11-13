package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

/**
 * Backdrop blur, as far as this module can honour it on 26.3.
 *
 * <p>The original captured the finished frame into an off-screen texture through
 * `GL_FRAMEBUFFER_BINDING`, ran two separable box-blur passes over it with `box_blur.vsh/fsh`, and
 * drew the result behind every frosted panel. Reading back the composed frame needs a
 * post-processing hook owned by the renderer, which a module cannot install: LiquidBounce's GUI
 * draws into the same graphics extractor the game does, so there is no point in the frame where the
 * module could capture "everything underneath" and no handle to hand a downsampled copy back to the
 * rounded-rect pipeline.</p>
 *
 * <p>So this object keeps the original's public surface — `request()`/`ready()`/`texture()` — and
 * reports that blur is unavailable. Every call site in the port already has the original's own
 * fallback branch (`surfaceContainer` at 0.93 alpha instead of a 0.55 surface over the blurred
 * backdrop), so the player keeps Onyx's tinted-glass look and only loses the frost texture. This is
 * deviation **R2** in the module README.</p>
 */
object Blur {

    /** True once a blur pass could be sampled; always false on 26.3. */
    fun ready(): Boolean = false

    /** Requests that the next frame be blurred; a no-op here. */
    fun request() {}

    /** Texture holding the blurred frame, or null when blur is unavailable. */
    fun texture(): Int = -1

    /** Captures and blurs the frame; a no-op here. */
    fun prepare() {}

    /** Installs the render-tick hook that invalidates the cached blur; a no-op here. */
    fun install() {}
}
