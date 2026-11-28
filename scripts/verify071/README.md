# 071: ClickGUI layout persistence regression harness

## Coverage

Runs **without launching Minecraft**, using official MC 26.3 APIs and small,
non-production fixtures for the unrelated configuration/rendering infrastructure.
Tests use temporary layout directories, never the player's real configuration.

- Compiles actual `ClickGuiLayoutStore`, `ClickGuiTabView`, `TabbedClickGuiScreen`,
  `GlobalSettingsTabView`, setting overlay, list widgets and common editor/localization sources.
- Extracts the actual `SettingNode`, six expandable widget classes and SwitchWidget
  from `SettingWidgets.kt`; does not compile the entire file or unrelated widgets.
- Copies actual `SettingRenderer` logic, replacing only its widget factory with a
  fixture factory for the subset under test.
- Simulates closing/reopening with partly completed expansion animations.
- Writes state then reads it in **a fresh JVM process**: panel XY/expanded/scroll,
  module row expansion, nested configurable groups, toggleable groups, explicit
  mode-group collapse, list expansion and list scrolling.
- Checks color, multi-choice, curve, named-item, registry and mutable-registry
  expansion in separate write/read JVM invocations.
- Same-named groups under different modules do not share state.
- Restored nested content height preserves scroll 1400 before/after EN/ZH sorting.
- Legacy layout migration, save-before-open, malformed-entry isolation and finite,
  nonnegative offsets.
- Reruns 070 search/sort/viewport boundary checks.

The warning from the intentionally malformed `Broken` entry is expected.

## Running

Requires JDK 25, Kotlin 2.4.20, Python 3, official Minecraft 26.3 client.jar and the
Minecraft libraries, Gson, SLF4J, etc. in a dependency jar directory (same setup as
070). No game binaries are included.

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/client.jar \
VERIFY_LIBS=/path/to/libs \
bash scripts/verify071/run.sh
```

This is not a complete Gradle build or graphical/native IME/Android gesture test.
