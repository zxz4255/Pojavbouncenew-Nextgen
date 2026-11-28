# 090 bounded verification

Use the actual MC26.3 / JDK25 / Kotlin2.4.20 / real manifest and VFP+nested+ASM JAR environment documented in verify089. No toolchain JARs are redistributed.

```sh
# Set JAVA_HOME, MC_JAR, MC_LIBS, VERIFY_LIBS, KOTLIN_LIBS as in verify089.
bash scripts/verify090/compile.sh
# moderngl numpy pillow, EGL:
python3 scripts/verify090/shader-test.py
# baseline is the immutable cloud-delivered089 archive (SHA256 locked):
python3 scripts/verify090/audit-package.py /path/to/089.zip /path/to/090.zip
```

`compile.sh` reuses explicit hosts from verify086 and verify089. Compiles complete production surface records and the three complete Kotlin surface emitters against actual game API types; exact Onyx hook, HUD scope/upgrade methods and routing excerpts retain the historical host limits. `glaze-hosts.py` additionally compiles the exact new two page-header guard/call excerpts, NOT the full ClickGuiTabView/GlobalSettingsTabView files. The script reruns the real production Java vertex regression (177024 assertions) and exact scope/routing regression (27006 assertions). No game starts.

`shader-test.py` uses only the raw transport helper definitions of verify089/shader-test.py; it does not run/write the historical089 checks/evidence. Compiles both immutable089 and current production material shaders with actual common SDF include; expands MC transform blocks to uniform declarations. The new GUI early-return path is tested for no spatial highlights, no rim/arc pass output, no lens response and one outside-only black shadow. H/V production blur fragments are compiled and run in the same four-pass/half-resolution arrangement using a small fullscreen-triangle harness. Constant response, high-frequency attenuation and clipped material coverage are checked. 84 HUD comparisons against immutable089 have maximum RGBA difference0 on the recorded EGL/Mesa renderer. Total95 assertions. Numeric equivalence is bounded to tested inputs and not a live-game assertion.

`offscreen-quiet-gui.png` is a production-shader sample over a synthetic checker/gradient. It is NOT a Minecraft/Android screenshot and deliberately contains no UI text or cursor. The optional self-contained review HTML clearly labels it.

`audit-package.py` locks the089 archive SHA256 and verifies exactly6 existing source modifications /2720 other baseline entries byte-identical /0 deletions. This retains all MVP/Neo inventory/knives/native cursor code, shared blur kernel/capture/pipeline code and historical evidence. All new files are documentation/evidence under090. CRC and every ZIP byte are rechecked.

No full Gradle build, entire GUI compilation, Mixin/bootstrap, true framebuffer scheduling, Pojav/Android GPU, screen-size or screenshot pixel acceptance. The no-FBO fallback is non-optical. NeoInvManager toggle-revert remains unresolved awaiting user logs; it is not altered or claimed repaired here.
