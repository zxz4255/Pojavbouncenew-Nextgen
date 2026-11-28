# 072 focused FPS rig verification

Run from any directory:

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/official-mc26.3-client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
bash scripts/verify072/run.sh
```

Dependency requirements match `../verify070/README.md`. No game or third-party binaries are included.

This compiles the production FPS Kotlin sources against the official Minecraft API with the existing unrelated config/module/event fixtures; it then runs `RigRegression.kt`, followed by the 070 Java mixin/geometry and 071 ClickGUI persistence suites. The new MouseButtonEvent fixture matches the project's WindowEvents.kt public fields, but event dispatch itself is not simulated.

The new tests cover physical-click/entity/touch input de-duplication, the bounded attack queue, airborne/manual inspect policy, vanilla hand-transform inversion, exposed handle endpoints, inward neutral arms, shared gripped transforms, sampled blade-body vertices outside the sleeve, and conservative depth separation of all mesh vertices (including articulated handles) during fully released inspection frames. Timeline methods are invoked reflectively for deterministic phase samples; tests are not screenshots and do not prove exact triangle collision freedom during every transition, any FOV or modified skin/model.

The whole-project classpath, actual Mixin application, deferred GPU rendering, Android/Pojav event delivery and native touch/IME remain untested. Full Gradle build plus target-device play testing are still required.
