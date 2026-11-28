# 092 diagnostic verification

Run with the JDK25/official MC26.3/Kotlin2.4.20 environment used by verify091:

```sh
bash scripts/verify092/compile-lifecycle.sh
```

`lifecycle-hosts.py` lists external hosts. Unlike 091, complete production `Value`, `ToggleableValueGroup`, `Toggleable`, `ClientModule`, `MinecraftShortcuts`, Neo module/hotkeys/planners/adapters and shared safe-session/policy compile together and execute the real Enabled delegated-property setter. ValueGroup is still a DSL/registration facade, not the whole production class. Configuration, language, events, bindings, logger and other-module/game services are hosted. No Minecraft game object or native window is created; attempts to obtain the hosted Minecraft singleton throw deliberately, and diagnostic fallback is verified not to veto activation.

The suite runs actual classes, not extracted activation method bodies. It verifies the original 091 pre-commit toggle-event order remains; this is deliberately an observational build, not a speculative framework rewrite. Neo-specific injected onChange rejection and external false write are correctly distinguished. Full writer file tests use a temporary directory and remove it. The independent diagnostic snapshot handler is invoked after disable, verifying it can still report actual/flow/running false; this is not live EventManager registration acceptance. 6148 assertions pass. Upstream inventory planner/safety implementation and MotionBlur are byte-preserved and are not claimed revalidated on a server/device.

`audit-package.py BASE091.zip OUTPUT092.zip` checks the immutable baseline SHA256, exactly three allowed modified production files plus three new diagnostic files, no changes to old tests/effects/safety implementations, only opt-in Neo tracing, allowed additions, ZIP CRC and exact per-entry byte equality. No full Gradle build, complete UI/Mixin compilation or Android/Pojav test has been performed. User's original reversion has not been reproduced. See `092-NEOINV-DIAGNOSTIC.md` for capture instructions.
