<script lang="ts">
    import {onMount} from "svelte";
    import type {Module as TModule} from "../../integration/types";
    import {listen} from "../../integration/ws";
    import Module from "./Module.svelte";
    import type {KeyboardKeyEvent, ModuleToggleEvent} from "../../integration/events";
    import {fade} from "svelte/transition";
    import {quintOut} from "svelte/easing";
    import {
        gridSize,
        highlightModuleName,
        maxPanelZIndex,
        scaleFactor,
        showGrid,
        snappingEnabled
    } from "./clickgui_store";
    import {setItem} from "../../integration/persistent_storage";

    export let category: string;
    export let modules: TModule[];
    export let panelIndex: number;
    export let icon: string | undefined = undefined;

    let panelElement: HTMLElement;
    let modulesElement: HTMLElement;
    let expandButtonElement: HTMLElement;

    let moving = false;
    let offsetX = 0;
    let offsetY = 0;

    let scrollPositionSaveTimeout: number | undefined;

    const panelConfig = loadPanelConfig();

    let ignoreGrid = false;

    interface PanelConfig {
        top: number;
        left: number;
        expanded: boolean;
        scrollTop: number;
        zIndex: number;
    }

    function showFallbackIcon(event: Event) {
        (event.currentTarget as HTMLImageElement).src = "img/clickgui/icon-client.svg";
    }

    function clamp(number: number, min: number, max: number) {
        return Math.max(min, Math.min(number, max));
    }

    function loadPanelConfig(): PanelConfig {
        const localStorageItem = localStorage.getItem(
            `clickgui.panel.${category}`,
        );

        if (!localStorageItem) {
            return {
                top: 72,
                left: 20 + panelIndex * 270,
                expanded: true,
                scrollTop: 0,
                zIndex: 0
            };
        } else {
            const config: PanelConfig = JSON.parse(localStorageItem);

            // Migration
            if (!config.zIndex) {
                config.zIndex = 0;
            }

            if (config.zIndex > $maxPanelZIndex) {
                $maxPanelZIndex = config.zIndex;
            }

            return config;
        }
    }

    async function savePanelConfig() {
        await setItem(
            `clickgui.panel.${category}`,
            JSON.stringify(panelConfig),
        );
    }

    function fixPosition() {
        panelConfig.left = clamp(panelConfig.left, 0, document.documentElement.clientWidth * (2 / $scaleFactor) - panelElement.offsetWidth);
        panelConfig.top = clamp(panelConfig.top, 0, document.documentElement.clientHeight * (2 / $scaleFactor) - panelElement.offsetHeight);
    }

    function onMouseDown(e: MouseEvent) {
        if (e.button !== 0 && e.button !== 1) return;

        moving = true;
        offsetX = e.clientX * (2 / $scaleFactor) - panelConfig.left;
        offsetY = e.clientY * (2 / $scaleFactor) - panelConfig.top;
        panelConfig.zIndex = ++$maxPanelZIndex;

        $showGrid = $snappingEnabled && !expandButtonElement.contains(e.target as HTMLElement);
    }

    function onMouseMove(e: MouseEvent) {
        if (moving) {
            const newLeft = (e.clientX * (2 / $scaleFactor) - offsetX);
            const newTop = (e.clientY * (2 / $scaleFactor) - offsetY);

            panelConfig.left = snapToGrid(newLeft);
            panelConfig.top = snapToGrid(newTop);

            fixPosition();
        }
    }

    function onMouseUp() {
        if (moving) {
            savePanelConfig();
        }
        moving = false;
        $showGrid = false;
    }

    function toggleExpanded() {
        panelConfig.expanded = !panelConfig.expanded;

        savePanelConfig();
    }

    /**
     * The panel only reaches its new height once the `max-height` transition of the module
     * list has finished, so the position can only be clamped here. Doing it right after the
     * toggle would measure the height the panel had *before* it was expanded or collapsed.
     */
    function handleModulesTransitionEnd(e: TransitionEvent) {
        if (e.target !== modulesElement || e.propertyName !== "max-height") {
            return;
        }

        const {left, top} = panelConfig;

        fixPosition();

        if (panelConfig.left !== left || panelConfig.top !== top) {
            savePanelConfig();
        }
    }

    function handleModulesScroll() {
        panelConfig.scrollTop = modulesElement.scrollTop;

        if (scrollPositionSaveTimeout !== undefined) {
            clearTimeout(scrollPositionSaveTimeout);
        }
        scrollPositionSaveTimeout = setTimeout(() => {
            savePanelConfig();
        }, 500)
    }

    highlightModuleName.subscribe((name) => {
        const highlightModule = modules.find(
            (m) => m.name === name,
        );
        if (highlightModule) {
            panelConfig.zIndex = ++$maxPanelZIndex;
            panelConfig.expanded = true;
            savePanelConfig();
        }
    });

    listen("moduleToggle", (e: ModuleToggleEvent) => {
        const moduleName = e.moduleName;
        const moduleEnabled = e.enabled;

        const mod = modules.find((m) => m.name === moduleName);
        if (!mod) return;

        mod.enabled = moduleEnabled;
        modules = modules;
    });

    onMount(() => {
        if (!modulesElement) {
            return;
        }

        modulesElement.scrollTo({
            top: panelConfig.scrollTop,
            behavior: "smooth"
        });
    });

    listen("keyboardKey", (e: KeyboardKeyEvent) => {
        if (e.key === "key.keyboard.left.shift") {
            ignoreGrid = e.action === 1;
        }
    });

    function snapToGrid(value: number): number {
        if (ignoreGrid || !$snappingEnabled) return value;

        return Math.round(value / $gridSize) * $gridSize;
    }
</script>

<svelte:window on:mouseup={onMouseUp} on:mousemove={onMouseMove}/>

<div
        class="panel"
        style="left: {panelConfig.left}px; top: {panelConfig.top}px; z-index: {panelConfig.zIndex};"
        bind:this={panelElement}
        transition:fade|global={{duration: 200, easing: quintOut}}
>
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div
            class="title"
            on:mousedown={onMouseDown}
            on:contextmenu|preventDefault={toggleExpanded}
    >
        <img
                class="icon"
                src={icon ?? `img/clickgui/icon-${category.toLowerCase()}.svg`}
                alt="icon"
                on:error={showFallbackIcon}
        />
        <span class="category">{category}</span>

        <!-- svelte-ignore a11y_consider_explicit_label -->
        <button class="expand-toggle" on:click={toggleExpanded} bind:this={expandButtonElement}>
            <div class="icon" class:expanded={panelConfig.expanded}></div>
        </button>
    </div>

    <div
            class="modules"
            class:expanded={panelConfig.expanded}
            on:scroll={handleModulesScroll}
            on:transitionend={handleModulesTransitionEnd}
            bind:this={modulesElement}
    >
        {#each modules as {name, enabled, description, aliases} (name)}
            <Module {name} {enabled} {description} {aliases}/>
        {/each}
    </div>
</div>

<style lang="scss">
  /* Samsara Opai-inspired palette and compact column surface. */
  .panel {
    border-radius: 10px;
    width: 250px;
    position: absolute;
    overflow: hidden;
    background: rgba(21, 19, 21, .88);
    border: 1px solid rgba(187, 195, 255, .14);
    box-shadow: 0 12px 34px rgba(0, 0, 0, .38), 0 0 0 1px rgba(255, 255, 255, .025);
    backdrop-filter: blur(14px);
    will-change: transform;
    transition: none;
    user-select: none;
  }

  .title {
    display: grid;
    grid-template-columns: max-content 1fr max-content;
    align-items: center;
    column-gap: 10px;
    background: #1c1f29;
    border-bottom: 1px solid rgba(187, 195, 255, .12);
    padding: 11px 13px;
    cursor: grab;

    .icon {
      width: 15px;
      height: 15px;
      opacity: .9;
      filter: brightness(1.35) saturate(.72);
    }

    .category {
      font-size: 12px;
      letter-spacing: .035em;
      color: #e4e1e6;
      font-weight: 650;
    }
  }

  .modules {
    transition: max-height 260ms cubic-bezier(.2,.8,.2,1);
    scroll-behavior: smooth;
    max-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    background: rgba(21, 19, 21, .94);

    &.expanded {
      max-height: 545px;
    }

    &::-webkit-scrollbar {
      width: 3px;
      height: 3px;
    }

    &::-webkit-scrollbar-thumb {
      border-radius: 4px;
      background: rgba(156, 165, 218, .6);
    }
  }

  .expand-toggle {
    background: rgba(255, 255, 255, .035);
    border: 1px solid rgba(255, 255, 255, .07);
    border-radius: 6px;
    width: 24px;
    height: 22px;
    display: grid;
    place-items: center;
    cursor: pointer;

    .icon {
      height: 10px;
      width: 10px;
      position: relative;

      &::before, &::after {
        content: "";
        position: absolute;
        background-color: #bfc5ff;
        transition: transform 0.25s ease;
        border-radius: 2px;
      }

      &::before {
        top: 0;
        left: 50%;
        width: 2px;
        height: 100%;
        margin-left: -1px;
      }

      &::after {
        top: 50%;
        left: 0;
        width: 100%;
        height: 2px;
        margin-top: -1px;
      }

      &.expanded::before { transform: rotate(90deg); }
      &.expanded::after { transform: rotate(180deg); }
    }
  }
</style>
