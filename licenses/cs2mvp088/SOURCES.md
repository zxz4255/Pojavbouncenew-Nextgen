# CS2 MVP banner reference measurements (088)

Reference repository: SteamDatabase/GameTracking-CS2, tracked Valve game UI.
Pinned commit: `4cb14a67c43ce55b8e077e9cd87de8fefcb1593c`.

- https://github.com/SteamDatabase/GameTracking-CS2/blob/4cb14a67c43ce55b8e077e9cd87de8fefcb1593c/game/csgo/pak01_dir/panorama/styles/hud/hudwinpanel.css
- https://github.com/SteamDatabase/GameTracking-CS2/blob/4cb14a67c43ce55b8e077e9cd87de8fefcb1593c/game/csgo/pak01_dir/panorama/layout/hud/hudwinpanel.xml
- https://github.com/SteamDatabase/GameTracking-CS2/blob/4cb14a67c43ce55b8e077e9cd87de8fefcb1593c/game/csgo/pak01_dir/panorama/scripts/hud/hudwinpanel.js
- https://github.com/SteamDatabase/GameTracking-CS2/blob/4cb14a67c43ce55b8e077e9cd87de8fefcb1593c/game/csgo/pak01_dir/panorama/styles/csgostyles.css
- https://github.com/SteamDatabase/GameTracking-CS2/blob/4cb14a67c43ce55b8e077e9cd87de8fefcb1593c/game/csgo/pak01_dir/resource/csgo_english.txt

`source-spec.json` records the fetched source hashes and factual layout/colour/timing measurements. This is not a redistribution of Valve's font, video, portrait-map, music or mask assets, nor a claim of a license to redistribute them. The Minecraft implementation is independently written project code; no Valve files are registered as runtime assets.

## Mapping

- `.MVP_section` / `.non-premier`: 640x90 banner (not the premier 920px character-map presentation).
- `.MVP__Avatar`: 72x72, 8px following gap.
- `.MVP__Reason`: 21px height, 18px text, 6px horizontal padding.
- `.MVP__WinnerName`: 250x38, 28px text, brightness1.5 and ellipsis.
- Team colours from `csgostyles.css`: CT `96C8FA`, T `EABE54`.
- Team background from `hudwinpanel.css`: CT `09283D`, T `422E08`, alpha0.85.
- Shown transform: 250ms ease-in-out. Local timed dismissal is a Minecraft adaptation; original lifecycle is a round-state lifecycle.
- The pinned English normal-kill MVP label is simply `MVP`, not an invented assertion that every single kill is the round's most-kills award.

## Explicit deviations / unverified areas

- User selected a kill-triggered UI strip only. There is no round-result title, no agent/camera map, no HUD replacement and no automatic victory claim.
- Root Y=190 (1080-high reference canvas) is reused for the standalone strip. In CS2, the strip ordinarily sits below the round-result section; that section is intentionally omitted here.
- Local Minecraft skin head and profile name replace the Steam portrait and winner identity.
- NameFont/ReasonFont select matching registered client font faces if available. The package does not bundle Stratum2. Existing UiDraw fallback uses Inter if the requested face is not registered, which is NOT exact original typography.
- The side fade is a procedural linear approximation, not Valve's original texture mask. Minecraft font-shadow rasterisation is also different from Panorama blur/shadow rasterisation.
- Original music kits/award sounds and the optional honour/music-kit rows are not included; no arbitrary substitute sound is presented as CS2 audio.
- Screen scaling, fixed hold time and hit-evidence attribution are Minecraft adaptations. Unknown legacy-protocol killer identity remains unknown; see root088 document.
- No side-by-side live-game screenshot or frame-accurate recording comparison has been performed. Numeric spec adherence is not proof of complete visual fidelity.
