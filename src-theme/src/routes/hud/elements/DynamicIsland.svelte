<script lang="ts">
    import {onDestroy} from "svelte";
    import {fly} from "svelte/transition";
    import {listen} from "../../../integration/ws";
    import type {FpsChangeEvent, ModuleToggleEvent, NotificationEvent, ServerPingedEvent} from "../../../integration/events";

    type Notice = { id: number; title: string; detail: string; tone: "success" | "warning" | "info"; };
    let fps = 0;
    let ping: number | null = null;
    let notice: Notice | null = null;
    let serial = 0;
    let dismissTimer: ReturnType<typeof setTimeout> | undefined;

    function show(title: string, detail: string, tone: Notice["tone"] = "info") {
        const id = ++serial;
        notice = { id, title, detail, tone };
        if (dismissTimer) clearTimeout(dismissTimer);
        dismissTimer = setTimeout(() => { if (notice?.id === id) notice = null; }, 2800);
    }

    listen("fps", (event: FpsChangeEvent) => { fps = event.fps; });
    listen("serverPinged", (event: ServerPingedEvent) => { ping = event.server.ping; });
    listen("notification", (event: NotificationEvent) => {
        const tone = event.severity === "SUCCESS" || event.severity === "ENABLED" ? "success"
            : event.severity === "ERROR" || event.severity === "DISABLED" ? "warning" : "info";
        show(event.title, event.message, tone);
    });
    listen("moduleToggle", (event: ModuleToggleEvent) => {
        if (event.hidden) return;
        show(event.moduleName, event.enabled ? "Module enabled" : "Module disabled", event.enabled ? "success" : "warning");
    });
    listen("disconnect", () => { ping = null; show("Disconnected", "You left the server", "warning"); });
    onDestroy(() => { if (dismissTimer) clearTimeout(dismissTimer); });
</script>

<div class="island" class:active={notice !== null} aria-live="polite">
    {#if notice}
        {#key notice.id}
            <div class="notice" in:fly={{ y: -8, duration: 180 }} out:fly={{ y: -6, duration: 140 }}>
                <span class="status-icon" class:success={notice.tone === "success"} class:warning={notice.tone === "warning"}>
                    {#if notice.tone === "success"}✓{:else if notice.tone === "warning"}!{:else}i{/if}
                </span>
                <span class="copy"><strong>{notice.title}</strong><small>{notice.detail}</small></span>
            </div>
        {/key}
    {:else}
        <div class="idle">
            <span class="brand-mark"></span>
            <strong>LIQUIDBOUNCE</strong>
            <span class="separator"></span>
            <span>{fps} FPS</span>
            {#if ping !== null}<span class="separator"></span><span>{ping} ms</span>{/if}
        </div>
    {/if}
</div>

<style lang="scss">
  .island {
    position: fixed;
    z-index: 999999;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    min-width: 148px;
    max-width: min(420px, calc(100vw - 24px));
    min-height: 24px;
    padding: 0 12px;
    box-sizing: border-box;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 1px solid rgba(187, 195, 255, .12);
    border-radius: 18px;
    background: rgba(20, 19, 23, .91);
    color: #e4e1e6;
    box-shadow: 0 6px 24px rgba(0, 0, 0, .34), inset 0 1px 0 rgba(255,255,255,.045);
    backdrop-filter: blur(14px);
    transition: min-height .22s cubic-bezier(.2,.8,.2,1), min-width .22s cubic-bezier(.2,.8,.2,1), border-radius .22s ease;
    pointer-events: none;
    font-family: inherit;

    &.active { min-height: 42px; min-width: 205px; border-radius: 13px; }
  }
  .idle, .notice { display: flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap; }
  .idle { height: 24px; font-size: 9px; letter-spacing: .025em; }
  .idle strong { font-size: 8px; letter-spacing: .09em; color: #bbc3ff; font-weight: 750; }
  .brand-mark { width: 6px; height: 6px; border-radius: 50%; background: #bbc3ff; box-shadow: 0 0 8px rgba(187,195,255,.65); }
  .separator { width: 1px; height: 10px; background: rgba(228,225,230,.22); }
  .notice { width: 100%; min-width: 0; justify-content: flex-start; gap: 9px; padding: 6px 0; }
  .status-icon { width: 23px; height: 23px; flex: 0 0 23px; display: grid; place-items: center; border-radius: 7px; background: rgba(90,145,190,.24); color: #c4dfff; font-size: 13px; font-weight: 800; }
  .status-icon.success { background: rgba(95,143,80,.25); color: #a8e29d; }
  .status-icon.warning { background: rgba(180,91,91,.25); color: #ffaaaa; }
  .copy { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
  .copy strong { color: #f0edf4; font-size: 10px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; }
  .copy small { color: rgba(228,225,230,.72); font-size: 9px; overflow: hidden; text-overflow: ellipsis; }
</style>
