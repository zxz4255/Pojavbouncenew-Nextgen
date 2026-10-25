<script lang="ts">
    import {onMount} from "svelte";
    import {
        getModuleSettings,
        setModuleSettings,
        setModuleEnabled,
    } from "../../integration/rest";
    import type {ConfigurableSetting} from "../../integration/types";
    import GenericSetting from "./setting/common/GenericSetting.svelte";
    import {slide} from "svelte/transition";
    import {quintOut} from "svelte/easing";
    import {description as descriptionStore, highlightModuleName} from "./clickgui_store";
    import {setItem} from "../../integration/persistent_storage";
    import {convertToSpacedString, spaceSeperatedNames} from "../../theme/theme_config";
    import {scaleFactor} from "./clickgui_store";

    export let name: string;
    export let enabled: boolean;
    export let description: string;
    export let aliases: string[];

    let moduleNameElement: HTMLElement;
    let configurable: ConfigurableSetting;
    const path = `clickgui.${name}`;
    let expanded = false;
    let hasSettings = false;

    onMount(async () => {
        await fetchModuleSettings();

        setTimeout(() => {
            expanded = localStorage.getItem(path) === "true"
        }, 500);
    });

    highlightModuleName.subscribe((m) => {
        if (name !== m) {
            return;
        }

        setTimeout(() => {
            if (!moduleNameElement) {
                return;
            }
            moduleNameElement.scrollIntoView({
                behavior: "smooth",
                block: "center",
            });
        }, 1000);
    });

    async function fetchModuleSettings() {
        configurable = await getModuleSettings(name);
        hasSettings = configurable.value.filter(v => v.name !== "Bind" && v.name !== "Hidden").length > 0;
    }

    async function updateModuleSettings() {
        await setModuleSettings(name, configurable);
        await fetchModuleSettings();
    }

    async function toggleModule() {
        await setModuleEnabled(name, !enabled);
    }

    function setDescription() {
        if (!moduleNameElement) return;

        const boundingRect = moduleNameElement.getBoundingClientRect();
        const y = (boundingRect.top + (moduleNameElement.clientHeight / 2)) * (2 / $scaleFactor);

        let moduleDescription = description;
        if (aliases.length > 0) {
            moduleDescription += ` (aka ${aliases.map(name => $spaceSeperatedNames ? convertToSpacedString(name) : name).join(", ")})`;
        }

        // If element is less than 300px from the right, display description on the left
        if (window.innerWidth - boundingRect.right > 300) {
            const x = boundingRect.right * (2 / $scaleFactor);
            descriptionStore.set({
                x,
                y,
                anchor: "right",
                description: moduleDescription
            });
        } else {
            const x = boundingRect.left * (2 / $scaleFactor);

            descriptionStore.set({
                x,
                y,
                anchor: "left",
                description: moduleDescription
            });
        }
    }

    async function toggleExpanded(e: MouseEvent) {
        e.stopPropagation();

        expanded = !expanded;
        await setItem(path, expanded.toString());
    }
</script>

<!-- svelte-ignore a11y-no-static-element-interactions -->
<div
        class="module"
        class:expanded
        in:slide={{ duration: 500, easing: quintOut }}
        out:slide={{ duration: 500, easing: quintOut }}
>
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <div
            class="name"
            on:contextmenu|preventDefault={toggleExpanded}
            on:click={toggleModule}
            on:mouseenter={setDescription}
            on:mouseleave={() => descriptionStore.set(null)}
            bind:this={moduleNameElement}
            class:enabled
            class:highlight={name === $highlightModuleName}
    >
        {$spaceSeperatedNames ? convertToSpacedString(name) : name}

        {#if hasSettings}
            <button
                    class="expand-arrow"
                    aria-label="Expand settings"
                    aria-expanded={expanded}
                    on:click={toggleExpanded}
            >
                <span class="expand-arrow-icon"></span>
            </button>
        {/if}
    </div>

    {#if expanded && configurable}
        <div class="settings">
            {#each configurable.value as setting (setting.name)}
                <GenericSetting {path} bind:setting on:change={updateModuleSettings}/>
            {/each}
        </div>
    {/if}
</div>

<style lang="scss">
  .module {
    position: relative;

    .name {
      cursor: pointer;
      display: flex;
      align-items: center;
      min-height: 34px;
      box-sizing: border-box;
      transition: background-color .16s ease, color .16s ease;
      color: rgba(228, 225, 230, .72);
      text-align: left;
      font-size: 11px;
      font-weight: 550;
      position: relative;
      padding: 8px 76px 8px 13px;

      &::before {
        content: "";
        position: absolute;
        right: 42px;
        top: 50%;
        width: 25px;
        height: 14px;
        border-radius: 10px;
        transform: translateY(-50%);
        background: #34333d;
        border: 1px solid rgba(255,255,255,.10);
        transition: background .18s ease, border-color .18s ease;
      }

      &::after {
        content: "";
        position: absolute;
        right: 53px;
        top: 50%;
        width: 8px;
        height: 8px;
        border-radius: 50%;
        transform: translateY(-50%);
        background: #858488;
        transition: right .18s ease, background .18s ease;
      }

      &.highlight {
        outline: 1px solid rgba(187, 195, 255, .85);
        outline-offset: -1px;
        border-radius: 4px;
      }

      &:hover {
        background: rgba(228, 225, 230, .055);
        color: #f4f2f7;
      }

      &.enabled {
        color: #e7e9ff;
        background: rgba(187, 195, 255, .055);

        &::before {
          background: #a8b0e7;
          border-color: #bbc3ff;
        }

        &::after {
          right: 45px;
          background: #172778;
        }
      }
    }

    .settings {
      background: rgba(29, 29, 38, .82);
      border-left: 2px solid rgba(187, 195, 255, .72);
      padding: 4px 10px 7px 9px;
    }

    .expand-arrow {
      all: unset;
      position: absolute;
      top: 0;
      right: 0;
      width: 36px;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      z-index: 1;
    }

    .expand-arrow-icon {
      width: 8px;
      height: 8px;
      display: block;
      background-image: url("/img/clickgui/icon-settings-expand.svg");
      background-position: center;
      background-repeat: no-repeat;
      opacity: .55;
      filter: brightness(1.7);
      transform: rotate(-90deg);
      transition: opacity .18s ease, transform .25s ease;
    }

    &.expanded .expand-arrow-icon {
      transform: rotate(0);
      opacity: .95;
    }
  }
</style>
