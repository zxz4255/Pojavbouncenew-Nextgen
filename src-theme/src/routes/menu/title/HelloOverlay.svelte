<script context="module" lang="ts">
    // The overlay is mounted each time the title route opens; the welcome animation replays on return.
    const MAIN_MS = 1650;
    const SUBTITLE_MS = 1000;
    const HOLD_MS = 250;
    const FADE_MS = 350;
    const FRAME_COUNT = 48;
    const COLUMNS = 5;
    // The title route itself is already mounted at this point. Do not let a stale native
    // screen/splash report keep the artwork invisible forever on a slow first Android launch.
    const SCREEN_READY_TIMEOUT_MS = 8000;
</script>

<script lang="ts">
    import {onMount} from "svelte";
    import {location} from "svelte-spa-router";
    import {portal} from "../../../integration/util";
    import {confirmVirtualScreen, getHelloScreenSettings, getVirtualScreen} from "../../../integration/rest";

    interface InkLayer {
        atlas: HTMLImageElement;
        delta: HTMLImageElement;
        source: HTMLImageElement;
        frameWidth: number;
        frameHeight: number;
        x: number;
        y: number;
        width: number;
        height: number;
    }

    let canvas: HTMLCanvasElement;
    let welcomeVisible = false;
    let welcomeOpacity = 1;
    let creditVisible = false;
    let showBuild = false;
    let build = "#unknown";
    let destroyed = false;
    let routeActive = false;
    let runToken = 0;
    let frameRequest = 0;
    let waitTimer = 0;

    const pause = (ms: number) => new Promise<void>(resolve => {
        waitTimer = window.setTimeout(resolve, ms);
    });

    async function settleWithin<T>(promise: Promise<T>, ms: number): Promise<T | undefined> {
        let timeout = 0;
        try {
            return await Promise.race([
                promise,
                new Promise<undefined>(resolve => {
                    timeout = window.setTimeout(() => resolve(undefined), ms);
                })
            ]);
        } finally {
            window.clearTimeout(timeout);
        }
    }

    function loadImage(name: string): Promise<HTMLImageElement> {
        return new Promise((resolve, reject) => {
            const image = new Image();
            image.onload = () => resolve(image);
            image.onerror = () => reject(new Error(`Could not load ${name}`));
            image.src = `img/hello/${name}`;
        });
    }

    function ease(t: number) {
        const p = Math.max(0, Math.min(1, t));
        return p * p * (3 - 2 * p);
    }

    function drawInk(ctx: CanvasRenderingContext2D, mask: CanvasRenderingContext2D,
                     ink: InkLayer, progress: number) {
        if (progress <= 0) return;
        if (progress >= 1) {
            ctx.drawImage(ink.source, 0, 0);
            return;
        }
        const framePosition = progress * (FRAME_COUNT - 1);
        const frame = Math.floor(framePosition);
        const fraction = framePosition - frame;
        const u = (frame % COLUMNS) * (ink.frameWidth + 2) + 1;
        const v = Math.floor(frame / COLUMNS) * (ink.frameHeight + 2) + 1;
        mask.clearRect(0, 0, mask.canvas.width, mask.canvas.height);
        mask.globalAlpha = 1;
        mask.drawImage(ink.atlas, u, v, ink.frameWidth, ink.frameHeight,
            ink.x, ink.y, ink.width, ink.height);
        if (fraction > 0) {
            // A source-over delta smoothly interpolates the reveal between atlas frames.
            mask.globalAlpha = fraction;
            mask.drawImage(ink.delta, u, v, ink.frameWidth, ink.frameHeight,
                ink.x, ink.y, ink.width, ink.height);
        }
        mask.globalAlpha = 1;
        // The broad atlas is just a reveal mask. The original-resolution smoothed ink
        // supplies the real outline and gradient, so scaling never blurs the final edges.
        mask.globalCompositeOperation = "source-in";
        mask.drawImage(ink.source, 0, 0);
        mask.globalCompositeOperation = "source-over";
        ctx.drawImage(mask.canvas, 0, 0);
    }

    async function waitForScreenReady(token: number) {
        const deadline = Date.now() + SCREEN_READY_TIMEOUT_MS;
        let titleSeen = false;
        while (!destroyed && token === runToken && Date.now() < deadline) {
            try {
                const screen = await settleWithin(getVirtualScreen(), 1500);
                if (destroyed || token !== runToken) return;
                if (!screen) {
                    console.warn("Native title status timed out; continuing with the Web title animation");
                    return;
                }
                if (screen.name === "title") {
                    titleSeen = true;
                    if (!screen.showingSplash) {
                        void confirmVirtualScreen("title").catch(() => undefined);
                        return;
                    }
                }
            } catch (error) {
                // The title component is mounted already; the native screen endpoint is only
                // a synchronization hint, so a temporary REST error must not suppress Hello.
                console.warn("Could not check native title readiness; continuing", error);
                return;
            }

            const remaining = deadline - Date.now();
            if (remaining <= 0) break;
            await pause(Math.min(120, remaining));
        }

        if (destroyed || token !== runToken) return;
        // Confirm only if native state has caught up to this already-mounted Web title.
        if (titleSeen) void confirmVirtualScreen("title").catch(() => undefined);
        console.warn("Native title readiness did not settle; continuing with the Web title animation");
    }

    async function start(token: number) {
        try {
            let settings: {animationEnabled: boolean; showBuild: boolean; build: string} | undefined;
            try {
                settings = await settleWithin(getHelloScreenSettings(), 5000);
            } catch (error) {
                console.warn("Could not read Hello settings; using the default enabled state", error);
            }
            if (destroyed || token !== runToken) return;
            if (!settings) {
                console.warn("Hello settings timed out; using the default enabled state");
                settings = {animationEnabled: true, showBuild: false, build: "#unknown"};
            }
            build = settings.build;
            showBuild = settings.showBuild;

            // The component only mounts on the title route. Native splash/status is a hint,
            // not a reason to leave the overlay invisible indefinitely on Android startup.
            await waitForScreenReady(token);
            if (destroyed || token !== runToken) return;

            if (!settings.animationEnabled) {
                creditVisible = true;
                return;
            }

            const images = await Promise.all([
                loadImage("hello_main_reveal_atlas.png"), loadImage("hello_main_reveal_delta_atlas.png"),
                loadImage("hello_main_smooth.png"), loadImage("hello_subtitle_reveal_atlas.png"),
                loadImage("hello_subtitle_reveal_delta_atlas.png"), loadImage("hello_subtitle_smooth.png")
            ]);
            if (destroyed || token !== runToken || !canvas) return;
            const ctx = canvas.getContext("2d");
            const scratch = document.createElement("canvas");
            scratch.width = canvas.width;
            scratch.height = canvas.height;
            const mask = scratch.getContext("2d");
            if (!ctx || !mask) throw new Error("Canvas unavailable");
            ctx.imageSmoothingEnabled = mask.imageSmoothingEnabled = true;
            ctx.imageSmoothingQuality = mask.imageSmoothingQuality = "high";
            const main: InkLayer = {atlas: images[0], delta: images[1], source: images[2],
                frameWidth: 382, frameHeight: 202, x: 0, y: 0, width: 764, height: 405};
            const subtitle: InkLayer = {atlas: images[3], delta: images[4], source: images[5],
                frameWidth: 344, frameHeight: 109, x: 374, y: 56, width: 688, height: 218};
            let beganAt: number | undefined;
            welcomeVisible = true;
            const animate = (now: number) => {
                if (destroyed || token !== runToken) return;
                if (beganAt === undefined) beganAt = now;
                const elapsed = now - beganAt;
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                drawInk(ctx, mask, main, ease(elapsed / MAIN_MS));
                drawInk(ctx, mask, subtitle, ease((elapsed - MAIN_MS) / SUBTITLE_MS));
                welcomeOpacity = Math.max(0, Math.min(1,
                    1 - (elapsed - MAIN_MS - SUBTITLE_MS - HOLD_MS) / FADE_MS));
                if (elapsed >= MAIN_MS + SUBTITLE_MS + HOLD_MS + FADE_MS) {
                    welcomeVisible = false;
                    creditVisible = true;
                    return;
                }
                frameRequest = requestAnimationFrame(animate);
            };
            frameRequest = requestAnimationFrame(animate);
        } catch (error) {
            // The page itself must remain usable even if the optional artwork cannot load.
            console.warn("Hello title artwork unavailable", error);
            if (!destroyed && token === runToken) creditVisible = true;
        }
    }

    // The component stays mounted while the router swaps pages. Watch the route itself so every
    // return to the title screen starts a fresh animation instead of silently doing nothing.
    $: if ($location === "/title" && !routeActive) {
        routeActive = true;
        destroyed = false;
        welcomeVisible = false;
        welcomeOpacity = 1;
        creditVisible = false;
        const token = ++runToken;
        void start(token);
    } else if ($location !== "/title" && routeActive) {
        routeActive = false;
        destroyed = true;
        runToken++;
        cancelAnimationFrame(frameRequest);
        clearTimeout(waitTimer);
    }

    onMount(() => () => {
        destroyed = true;
        runToken++;
        cancelAnimationFrame(frameRequest);
        clearTimeout(waitTimer);
    });
</script>

{#if $location === "/title"}
    <div class="hello-layer" use:portal aria-hidden="true">
        <canvas class:visible={welcomeVisible} style:opacity={welcomeOpacity}
                width="1062" height="405" bind:this={canvas}></canvas>
        <div class="credit" class:visible={creditVisible}>
            <img src="img/hello/credit.png" alt=""/>
            {#if showBuild}<div class="build">{build}</div>{/if}
        </div>
    </div>
{/if}

<style>
    .hello-layer {
        position: fixed;
        inset: 0;
        z-index: 10000;
        pointer-events: none;
        overflow: hidden;
        background: transparent;
    }
    canvas {
        display: block;
        position: absolute;
        width: min(82vw, 139vh);
        height: auto;
        max-height: 53vh;
        left: 50%;
        top: 50%;
        transform: translate(-50%, -50%);
        visibility: hidden;
        pointer-events: none;
    }
    canvas.visible { visibility: visible; }
    .credit {
        position: absolute;
        right: clamp(14px, 2.2vw, 36px);
        bottom: max(54px, 7vh);
        width: clamp(120px, 15vw, 215px);
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: 3px;
        opacity: 0;
        transition: opacity 650ms ease-out;
    }
    .credit.visible { opacity: 1; }
    .credit img {
        display: block;
        width: 100%;
        height: auto;
        /* The photographic pen mask stays transparent; white ink is legible on the dark title. */
        filter: invert(1) drop-shadow(0 1px 2px rgba(0, 0, 0, .65));
    }
    .build {
        color: rgba(255, 255, 255, .72);
        font: 500 12px/1.2 Inter, sans-serif;
        letter-spacing: .04em;
        text-shadow: 0 1px 2px rgba(0, 0, 0, .7);
    }
    @media (max-height: 480px) {
        .credit { width: clamp(90px, 13vw, 150px); bottom: 40px; }
        .build { font-size: 10px; }
    }
</style>
