# 029 — Android 原生音频 + MoonLight KillAura / ChestAura 移植

本包是把 `Pojavbouncenew-Nextgen-No-ChatCard-CompileFix.zip`（028 基线）改出来的**源码包**，
没有构建产物，按你的要求**未构建**。

## 1. Music 模块：维度和远距离 TP 不再停顿

**原因**：028 的音乐输出是 Minecraft `SoundEngine` 里的一条 streaming channel
（`MinecraftAudioOutput` + `MusicSoundLifecycle` + `MixinSoundEngineLifecycle`）。维度切换、重生、
进世界、资源重载、音效设备切换都会走 `SoundEngine.stopAll()`，把这条 channel 收回；播放必须等引擎
重新给出新 channel 才能继续，所以听感上就是"停一下再恢复"。`AudioEngine.handleWorldReload()`
当时还会主动关掉输出、等下一轮 worker 重建，这个停顿就更明显。

**改法**（全部新写，不是改参数）：

- 新增 `utils/audio/android/AndroidAudioTrackOutput.java`：直接反射调用平台 `AudioTrack`
  （`MODE_STREAM`、`ENCODING_PCM_16BIT`、`USAGE_MEDIA/CONTENT_TYPE_MUSIC`），自带约 1 秒的
  写入预算、播放头时钟、pause/flush/close、设备死亡检测。反射是为了不在桌面编译期依赖
  `android.jar`（`AndroidMediaCodecAudioParser` 原来也是这么做的）。
- 新增 `utils/audio/android/AndroidAudioSession.java`：运行时代检测、sink 注册与 generation。
- 新增 `modules/misc/music/AndroidAudioOutput.kt`：把 `write/pause/flush/drain` 接到上面的 sink。
- 重写 `AudioEngine.kt`：输出只剩 `AudioTrack`；`handleWorldReload()` 变成**空操作**——
  设备不属于游戏的音频库，维度切换/远距离 TP 都碰不到它，所以不会再有中断再恢复。
  `handleClientShutdown()`/`dispose()` 仍然会同步关掉设备（避免退出后还在响）。
- 删除 `MinecraftAudioOutput.kt`、`MusicSoundLifecycle.kt`、`MixinSoundEngineLifecycle.java`、
  `MixinSoundEngineAccessor.java`、`MixinSoundManagerAccessor.java`，并从 `liquidbounce.mixins.json`
  里去掉对应条目。音乐模块不再持有、也不再改写游戏混音器的任何 channel。

## 2. 删除 Java Sound，只用 Android 原生库解码

- `AudioEngine.kt` 里所有 `javax.sound.sampled.*`（`AudioFormat`/`AudioSystem`/`DataLine`/
  `SourceDataLine`/`LineUnavailableException`）全部移除，桌面 Java Sound 输出分支删除。
- `ModuleFriendswine.kt` 的 `AudioSystem.getClip()`/`Clip`/`FloatControl` 换成新写的 RIFF/WAVE
  读取 + `AudioTrack` 播放。
- 解码侧：Android 上 `MusicAudioParserFactory` 首选 `AndroidMediaCodecAudioParser`
  （`MediaExtractor` + `MediaCodec`，平台原生 codec）。这次把 **Ogg/Vorbis 也交给平台 codec**
  （`audio/vorbis` 是 Android 自带解码器），所以 `AudioEngine` 里原来单独的 Ogg 分支、以及对
  Minecraft `JOrbisAudioStream` 的依赖全部删除，四种格式（MP3/FLAC/MP4-AAC/Ogg-Vorbis）现在
  走同一条 `runPcmTrack` 管线。
  `MusicAudioParserFactory` 里保留的 `Mp3AudioParser`/`FlacAudioParser`/`M4aAudioParser` 等纯 Java
  解码器是**桌面回退**：桌面 JVM 没有 `android.media`，不保留它们源码在桌面就跑不起来；
  Android 运行时不会走这些路径（`open()` 先试 MediaCodec）。Ogg 没有桌面回退解码器，
  平台 codec 不可用时会明确报错，而不是假装能播。
- Music 界面里"Minecraft 独立音频通道"开关改成只显示输出方式（只有 `Android AudioTrack`，
  没有第二个可选设备了）。

## 3. MoonlightKillAura（自己写，1.8 旧包）

文件：`features/module/modules/combat/ModuleMoonlightKillAura.kt`
辅助：`combat/moonlight2/MoonlightRotation.kt`、`combat/moonlight2/Moonlight1_8Packets.kt`

- **转头模式**（`AimPoint` × `RotationMode` × `RotationJitter` × `MovementCorrection`）：
  `Center/Nearest/Head/Body/Feet` 五种瞄点；`Linear/Sine/Quadratic/Cubic/Adaptive/Random`
  六种缓动；每 tick 有 `YawSpeed`/`PitchSpeed` 角度预算，按"剩余角度/速度"估算剩余 tick 数，
  所以缓动曲线会落在准备点击的那一 tick；`Silent` 时只能靠改写 C03/C04（`ServerboundMovePlayerPacket`
  的 yRot/xRot 字段）让服务端看到假转头，屏幕不动；`ChangeLook` 时通过
  `RotationManager` 真正转视角。
- **AutoBlock**：`None/Vanilla/Interact/InteractAt/Watchdog/Hypixel/Blink`。
  1.8 剑格挡 = C08 空中使用（`BLOCK_POSITION1_8(-1,-1,-1)` + face 255 + `ITEM1_8`），
  `Interact`/`Watchdog`/`Hypixel` 先发 C02 action 0，`InteractAt` 发 C02 action 2（带坐标）；
  `ReleaseOnAttack`+`ReleaseTicks` 处理"1.8 必须先松格挡才吃伤害"的时序；
  `BlockOnlySword`、`AutoBlockRange` 可调。
- 目标：`Single/Switch/Multi`、`Priority=Distance/Health/Armor/HurtTime`、
  `SearchRange`/`RotationRange`/`AttackRange`/`ThroughWallsRange`/`FOV`、
  Players/Mobs/Animals、Invisible/Teams/Friends/AntiBot/IgnoreNaked。
- 攻击：`MinAPS/MaxAPS/Random|Fixed`、`PerfectHit`+阈值、`HitSelect`+阈值、`RayCast`、`Swing`。
- 所有 1.8 包都走 `Moonlight1_8Packets`：每次调用重新校验 ViaFabricPlus 连接（`isActive`、
  `serverState == PLAY`、目标版本 1.8、channel 打开），失败返回 `false` 而不是抛异常，
  所以掉线/换协议不会把客户端 tick 带崩。

## 4. MoonlightChestAura（移植 + 静默）

文件：`features/module/modules/combat/ModuleMoonlightChestAura.kt`（注册在 Player 分类，别名 `ChestAura`）

- `OpenMode = Legacy | Silent`。
  - `Legacy`：走客户端自己的 `gameMode.useItemOn`，一 tick 一个容器，等服务器回容器界面。
  - `Silent`：不关容器界面，连续开下一个；`SilentOpen` 会先发 1.8 的 C16
    （client command 2，open inventory），满足需要"先开背包"才吃点击的服务器。
- 选项：`Range`/`WallRange`/`Delay`/`MaxPerTick`/`CloseWhenDone`/`Swing`/
  `PauseInCombat`/`PauseWhileUsing`/`RotateOnly`/`SkipBlockedChests`/`SkipUpperShulkers`，
  可交互方块集合默认由注册表推导（`*_chest`、`*_shulker_box`、`barrel`、`furnace`、
  `hopper`、`dispenser`、`dropper` + 酿造台）。
- 双箱只开一次（跟 `anotherChestPartDirection`），被压住的箱子直接跳过；手动开过的箱子会记账，
  不会重复开。访问记录有上限，长时间挂机不会无限涨。
- 转向走共享 `RotationManager`，优先级低于战斗，和 MoonlightKillAura 同时开不会互相抢视角。

## 5. 注册与其它改动

- `ModuleManager.registerInbuilt()` 增加 `ModuleMoonlightChestAura`（紧跟 `ModuleMoonlightKillAura`）。
- AutoGap 自己的 1.8 发包/队列保持原样（它和这次的 KillAura/ChestAura 是两套独立实现）。
- 删除针对旧月光实现的测试（`MoonlightAuraMathTest`、`MoonlightBurstOrderTest`、`MoonlightLegacyPacketsTest`、
  `MoonlightNettyOrderTest`、`MoonlightPacketQueueTest`），新增
  `src/test/java/.../combat/moonlight2/MoonlightRotationTest.java`（纯数学，不需要游戏环境）。
- `HelloScreenGameTest` 增加 ChestAura 注册检查。
- KillAura 的 `PerfectHit` 改用原版攻击冷却刻度（`getAttackStrengthScale`）；原版 26.3 的
  `LivingEntity` 已经没有 `attackAnim` 字段，之前那行是编译不过的。
- 静默转头只改写 `Rot`/`PosRot` 两种移动包（`StatusOnly` 没有旋转字段，跳过）。
- 第一次编译反馈的修正：`enumSetOf` → `multiEnumChoice("Targets", TargetKind.PLAYERS)` 的 vararg
  重载；`boost` 保持 `Float`（不再被 `coerceAtLeast(0.0)` 推成 `Double`）；
  `self.swing(hand)` → `SwingMode.DO_NOT_HIDE.swing(hand)`（26.3 的 `LocalPlayer.swing` 需要动画参数）；
  `sink.setVolume` → `sink.setGain`（`AndroidAudioTrackOutput` 只有 `setGain`）；
  删除不存在的 `ServerboundPackets1_8.ANIMATION` / `PLAYER` 两个常量以及依赖它们的
  `sendSwing()` / `sendGround()`：1.8 的格挡是靠"不再发 C08"自然解除的，不需要松格挡包，
  KillAura 里那个从未被置位的 `serverGroundSpoofed` 字段一起删掉。

## 6. 未验证 / 已知边界

- **没有构建**：按要求没有执行 Gradle。改动经过人工逐项核对（导入、调用签名、访问器允许写
  `ServerboundMovePlayerPacket` 的 yRot/xRot、`RotationUpdateEvent`/`GameTickEvent` 用法），
  但**没有编译验证**，也没有在真机/实服上跑过。
- 所有格式都依赖平台 codec：设备缺少对应解码器（极少见，Vorbis 属于 AOSP 标配）时该格式会
  报"需要平台解码器"，不会静默失败。
- 1.8 适配仅在 `isEqual1_8`（ViaFabricPlus 目标协议 47）下启用；其它协议下 AutoBlock 退回
  客户端自己的 `startUsingItem`。
- AutoBlock 的 `Watchdog`/`Hypixel` 目前与 `Interact` 的包序相同（C02 + C08），没有做过针对这两家
  反作弊的单独调参；能不能过反作弊无法保证。
- ChestAura 的 `Silent` 模式在部分服务器会因为"没关容器就开下一个"被判定异常，`Legacy` 是保守选项。

---

# 030 — 实机日志（latest.log, 11:28–11:37）分析后修正

## 先纠正一个前提
这份 latest.log 里 **没有本包的痕迹**：
- `MoonlightKillAura` 只出现在 11:29:06 的 "missing fallback description key"，说明它已经从**你之前那份基线包**（`ModuleMoonlightKillAura` 用的是旧 `BlockMode`：None/Vanilla/HYT/Watchdog/Release/Interact）注册成功；
- 全日志 0 次 `stage=`、0 次 `Android AudioTrack`、0 次 `MoonlightChestAura` / `ChestAura` —— 029 的音频新代码和新 ChestAura 都没跑过（029 里 `AudioEngine` 每次换曲都会打 `[Music audio] stage=…`，这份日志一次都没有）；
- `[Music audio] Android MediaCodec unavailable for MP4_AAC; Java decoder fallback is disabled on Android` 这句话在 029 的源码里根本不存在（029 是 `using the bundled Java decoder`）。

所以这次"崩溃"不是 029 引起的，日志是旧构建跑出来的。下面是我从这份日志里**确实能定位**的问题，以及 030 的改法。

## 1. 真正把客户端打断的：客户端 ↔ 代理协议不同步
11:32:41–11:32:42 时序：
1. 11:32:41 `Already connecting to this server!` → 重复连接/重连
2. 大量 `Received packet for unknown scoreboard objective` / `unknown team`
3. 11:32:42 `§c服务器异常，您已被踢出 : Illegal combat modifications.`（服务端踢人）
4. 之后 11:34:15 换了一个 **1.12.2** 的大厅（`(1.12.2->1.13)` 的 ViaLegacy 警告），再进 SkyWars
5. 11:37:07 开始刷 `mount_screen_open ... 31 bytes extra`、`remove_mob_effect: No value with id 61`、
   `player_chat: 66 elements exceeded max size`、`Received unknown packet id`、
   `bundle_delimiter ... 290000 bytes extra`、`ERROR IN Protocol1_20_2To1_20_3 IN REMAP OF ...`

第 5 步是典型的 **ViaVersion 状态错位**：`mount_screen_open` 多 31 字节、`bundle_delimiter` 多 29 万字节、
`unknown packet id 5198`，说明字节流从某个包开始就再也对不上，客户端随后解出一堆垃圾包。常见的三种诱因：
1. 同一时刻有两条连接/一次重连没清干净（日志第 1 步就是重复连接）；
2. 服务端是 1.12.2 而用户端把 ViaFabricPlus 目标版本设成了 1.8（`isEqual1_8`）；
3. 有模块在 1.8 目标下往流里塞了手工构造的包，字段写歪一个 SHORT/VARINT，后面整条流就废了。

**030 的处理（服务端侧无法改，客户端侧能做的都做了）**
- `AutoBlock` 现在是"只有 1.8 目标才生效"：`updateAutoBlock()` 第一行就是 `if (!isEqual1_8) { releaseBlock(); return }`。
  在 1.8 以上的目标上，原来会退化成裸发 C08 use-item 包 —— 这正是服务端回 `Illegal combat modifications` 的典型特征。
- 手工 1.8 包全部继续走 `Moonlight1_8Packets`，每次发送前校验 `serverState == PLAY`、目标版本 == 1.8、channel 打开；
  校验失败返回 `false`，**不会再往一个非 1.8 的流里写 1.8 字段**。

**需要你确认的两件事（这两件我改不了，但它们决定还会不会复现）**
1. 你连的那个服（`mchvh.org` / WeiPuYun 大厅）是 **1.12.2**。ViaFabricPlus 的"目标版本"要设成 1.12.2（或 Auto），
   **不要**设 1.8 —— 设成 1.8 但服务端是 1.12.2 时，`isEqual1_8` 会返回 true，1.8 的 C08/C02 会被塞进 1.12.2 的流里，正好就是上面那串错位。
   （`isEqual1_8` = "ViaFabricPlus 选的目标版本是 1.8"，不是"服务器是老版本"。）
2. 那份 `modules.json` 里 `MoonlightKillAura` 的 `AutoBlock` 存的是 `Watchdog`。030 把选项标签补回了旧值
   （`None/Vanilla/HYT/Watchdog/Release/Interact` + 新增 `InteractAt/Hypixel/Blink`），并且 `HYT`、`Release` 是**真的实现了的模式**，不是别名占位。
   但更稳的做法是：进游戏后把这个模块的 `AutoBlock` 设成 `None` 先确认不再被踢，再逐项打开。

## 2. 日志里确认的、与我们代码无关但会炸的地方
`PotionFXPlayers.kt:98` 每次渲染都抛 `java.util.NoSuchElementException: No value present`
（`PotionContents.getColorOptional(...).asInt`：当目标的所有药水效果都不可见时 `OptionalInt` 是空的）。
这是基线代码的问题，但它在每帧渲染里抛，Android 上足够拖到 ANR/被杀。030 顺手修了：

```kotlin
private fun effectColor(entity: Entity): Int =
    getColorOptional(entity.activeEffects).toNullable() ?: 0xFFFFFFFF.toInt()
```
两处 `drawSquareTexture` 都改成用它，`OptionalInt` 为空时用不透明白，不再抛异常。

## 3. 音乐：Android 上 MediaCodec 反射失败时会给出真正的失败原因
日志：`Android MediaCodec API 不可用`（外层信息），实际原因被吞在 `IOException` 的 cause 里。
030 把三层包装都补了原因：
- `AndroidMediaCodecAudioParser`：`Android API 方法不可用：<Class>.<method>（Pojav 的 android.jar 里缺少该成员）`
- `MusicAudioParserFactory`：新增 `rootCause()`，打出最内层那句话，不再是笼统的"不可用"
- 下次再出这个问题，日志里会直接看到是哪个方法/字段在 Pojav 的 android.jar 里缺

这一条只是诊断改良：**Pojav 的 android.jar 是编译期桩，缺成员是常态**，`AudioTrack` 那条路径（029 新写的）
同样只用最基础的方法（`getMinBufferSize/write/play/pause/flush/stop/release/getPlaybackHeadPosition`），
但它也还没在你的设备上跑过一次 —— 029 的 `stage=` 一行都没出现就是证据。

## 4. 其它日志噪音（不影响，未改）
- `Error during loading of BedrockProtocol`：ViaBedrock 与 ViaVersion 5.12.3 不匹配，Bedrock 目标版本不可用；不影响 Java 服。
- `Failed to fetch user properties / Realms / LiquidChat 401`：账号/网络问题。
- `Invalid path ... INTER-LICENSE.txt`：资源包里的许可证文件被当成资源，无害。
- `Couldn't leave fullscreen`：Pojav/SDL 在 Android 上的正常警告。
- `MoonlightKillAura is missing fallback description key` / `ArmorBreak ...`：030 已给
  `moonlightKillAura` / `moonlightChestAura` / `moonlightAutoGap` 补了 en_us 与 zh_cn 的描述键。

## 5. 下一步
1. 用 **030 这个包**重新构建，进服前确认 ViaFabricPlus 目标版本 = 服务端真实版本（大厅是 1.12.2）。
2. 第一次测试请只开 `MoonlightKillAura`，`AutoBlock = None`、`RotationMode` 默认，先打两分钟；
   日志里搜 `stage=`（音乐）、`Moonlight`（模块）确认新代码是否真的在跑。
3. 如果还被踢，把带 `Illegal combat modifications` 那一行前后 30 行贴给我 —— 需要知道踢人时服务器目标版本是多少，
   以及当时开了哪些模块。

---

# 031 — ChestStealer 崩溃修正（原版模块）

`ChestStealer` 是 LiquidBounce 原版模块（`player/cheststealer/`），本包没有改它的逻辑，只把
「容器在规划过程中变了」这一类必然崩溃的点全部堵住。原版就是直接崩（用 `!!` / `maxOf` /
`as AbstractContainerScreen<*>` / `menu.slots[i]`），在 Android 上会被放大成 ANR 或直接闪退。

## 1. `ModuleChestStealer.kt`
- `handler<ScheduleInventoryActionEvent>` 的体整体包进 `try/catch`：规划期抛异常只打一条
  `ChestStealer failed to plan the next container action` + 堆栈，不再打断这一整个 tick 的
  库存调度（`InventoryManager` 的 do/while 里，一个 handler 抛出会带走全部模块的调度）。
- 取出 `processContainer(event)`（原 lambda 内容原样搬过去，`return@handler` → `return`）。
- 循环里新增 `if (slot.itemStack.isEmpty) continue`：计划是在同一个 tick 更早时生成的，
  槽位可能已经被服务端重同步、被别的玩家拿走、或被上一次 quick-move 清空。
- `ItemCategorization.Default.getItemFacets(slot).maxOf { ... }` → `maxOfOrNull { ... } ?: Priority.NORMAL`。
  **这是最可能要命的一条**：`getItemFacets` 对空栈返回 `emptyList()`，`maxOf` 对空集合抛
  `NoSuchElementException`；这正好在「槽位刚被清空」时触发，也就是 ChestStealer 的常态。
- `findPossiblePickActions` 开头：空来源栈直接 `return null`（空栈的 `mergeableCapacityFor`
  会把每个空背包槽算成容量 1，导致对着空槽发 quick-move）。
- 新增 `import ...utils.kotlin.Priority`。

## 2. `utils/inventory/ItemSlot.kt`（`ContainerItemSlot.itemStack`）
```kotlin
override val itemStack: ItemStack
    get() {
        val slots = (mc.gui.screen() as? AbstractContainerScreen<*>)?.menu?.slots ?: return ItemStack.EMPTY
        return slots.getOrNull(this.slotInContainer)?.item ?: ItemStack.EMPTY
    }
```
原来是无条件 `as AbstractContainerScreen<*>` + `slots[index]`：箱子关掉/换成别的界面、
或服务端重发 `ClientboundContainerSetContent` 使槽位变少时，会抛
`ClassCastException` / `IndexOutOfBoundsException`。这个 getter 被 ChestStealer、InventoryCleaner、
`Slots.HotbarAndInventory` 到处调用，是最可能的崩溃现场。

## 3. `utils/inventory/InventoryAction.kt`（`Click.performAction`）
- 发点击前重新解析「当前真正打开的界面」：`currentScreen.syncId != screen.syncId` → 直接 `return false`；
  `containerId` 用当前界面的；`slotId` 用当前界面算，并检查 `slotId in menu.slots.indices`。
- 原来用的是排程时捕获的 `screen`（可能已关闭），拿旧 `containerId`/旧槽位号发
  `ServerboundContainerClickPacket`，会让窗口 desync，也容易被反作弊当成异常交互。

## 4. `OpaiChestLoot.kt`（Dynamic Island 的 ChestLoot 面板，渲染路径）
- `update()` / `draw()` 各自包 `try/catch`：失败只 `logger.error` + 丢 session，
  不再让渲染回调抛异常（渲染线程抛异常 = 闪退 / 黑屏）。
- `onCandidates`：session 的界面必须仍是当前界面（`mc.gui.screen() !== screen` 时丢弃），
  且 `observe()` 用局部 `active` 非空引用（原来 `current` 是 `Session?`，NPE 风险）。
- `Session.previous` 的初始化改用 `slots.getOrNull(id)?.item ?: ItemStack.EMPTY`
  （原来 `screen.menu.slots[it]`，重同步后索引越界）。

## 5. `FeatureSilentScreen.kt`
- `mc.gui.screen() as AbstractContainerScreen<*>` → `as?` + 空判断 + `player/level` 空判断。
  `shouldHide` 是 ScreenEvent 时算的，画 overlay 时箱子可能已经关了。
- `SilentScreen` 打开时箱子被关/界面被换是崩溃高发点，这一处是硬转换。

## 6. `ModuleInventoryCleaner.kt` / `items/FoodItemFacet.kt` / `items/MiningToolItemFacet.kt`
ChestStealer 在 `InventoryCleaner` 开启时用它的 `CleanupPlanGenerator`，所以顺带堵住：
- `handleInventorySchedule` 包 `try/catch`，逻辑搬到 `processInventorySchedule`。
- `processHotbarSwaps` 的 `require(hotbarSwap.to is HotbarItemSlot)` → 改成
  `firstOrNull { it.to is HotbarItemSlot }`，跳过不支持的换位目标（原来 `require` 抛出会带走整个调度）。
- `FoodItemFacet` 的 `foodComponent!!` → 可空比较（模组物品 / VFP 翻译出来的栈可能没有食物组件，
  抛在 `Comparator` 里更难看）。`providedItemFunctions` 同样去 `!!`。
- `MiningToolItemFacet` 的 `error("Item ... is not a mining tool")` → 返回新增的 `UNKNOWN_TOOL = 0`，
  模组工具不再在 `category` 取值时抛异常。

## 7. 排查建议
如果 031 还是崩，日志里这几条已经能定位：
- `ChestStealer failed to plan the next container action` → 规划期（库存/计划生成）
- `InventoryCleaner failed to plan the next inventory action` → 清理计划
- `[ChestLoot] update failed` / `[ChestLoot] draw failed` → 面板渲染
三条都会带完整堆栈。若一条都没有、直接闪退，那就是 JVM 层（Pojav），需要 `latest.log` 尾部 +
`hs_err` 或 Pojav 的 crash log。

## 045 —— 抓到真凶：AutoArmor 护甲评估在渲染线程上被重复跑上百次

### 追踪器给出的证据

042 装的 `FreezeTracker` 第一次出货就抓到了，两份报告（2446 行）指向同一处：

```
[RUNNABLE] Render thread  id=3
    at net.minecraft.world.item.ItemStack.isEmpty(ItemStack.java:334)
    at net.minecraft.world.item.ItemStack.getComponents(ItemStack.java:226)
    at net.minecraft.core.component.DataComponentHolder.get(DataComponentHolder.java:12)
    at ...ItemCategorizationsKt.getEquippableComponent(ItemCategorizations.kt:103)
    at ...ItemCategorizationsKt.getEquipmentSlot(ItemCategorizations.kt:106)
    at ...armor.ArmorPiece.getToughness-impl(ArmorPiece.kt:46)
    at ...armor.ArmorEvaluation.findBestArmorPieces(ArmorEvaluation.kt:44)
    at ...autoarmor.ModuleAutoArmor.scheduleHandler$lambda$0(ModuleAutoArmor.kt:114)
    at ...event.EventManager.callEvent(EventManager.kt:429)
    at ...InventoryManager$...tickHandler.invokeSuspend(SuspendHandlers.kt:204)
    ...
    at net.minecraft.client.Minecraft.tick(Minecraft.java)
```

第二份报告的栈顶更明确，直接卡在**比较器内部**：

```
[RUNNABLE] Render thread
    at ...ArmorComparator$Companion$SHARED_COMPARATOR$1.invoke(ArmorComparator.kt:165)
    at ...ComparatorChain.compare(ComparatorChain.kt:29)
    at ...ArmorComparator.compare(ArmorComparator.kt:181)
    at kotlin.collections.CollectionsKt___CollectionsKt.maxWithOrNull(_Collections.kt:2631)
    at ...ArmorEvaluation.findBestArmorPieces(ArmorEvaluation.kt:53)
```

关键特征：

- **线程状态是 `RUNNABLE`，不是 `BLOCKED`/`WAITING`** → **不是死锁**，是纯粹的算力耗尽；
- **没有 native 栈帧** → 不是上次那类 `.so` 崩溃；
- GC 只有 177 次 / 累计 5.0 s，堆用量 673 MB / 上限 1586 MB → **不是内存压力**；
- 时间线：`17:56:06.957 ContainerScreen 关闭` → `17:56:51` 写第一份报告，正好是**连续开箱结束后**。

所以：**ChestAura 连续开箱 → ChestStealer 每箱都跑一遍仓库调度 → AutoArmor 每次调度都做全量护甲评估
→ 渲染线程被这些评估拖住 6 秒。**

这解释了用户最初说的「**不是 music 模块的问题，正常用也会卡死**」——确实不是。

---

### 根因

`InventoryManager` 的调度器是个 `do/while`：

```kotlin
var cycles = 0
do {
    cycles++
    if (cycles > 100) { chat("...too long..."); break }   // 上限 100
    requiresUpdate = false
    val event = EventManager.callEvent(ScheduleInventoryActionEvent())   // ← 每轮全量重跑
    ...
    // 每次等待都重置计数器：
    if (recentInventoryOpen) { ...; cycles = 0 }
    if (requiresPlayerInventory) { ...; cycles = 0 }
    if (canCloseMainInventory) { ...; cycles = 0 }
    if (action is CloseScreen) { ...; cycles = 0 }
    if (action.performAction()) { ...; cycles = 0 }
} while (schedule.isNotEmpty())
```

两个致命点：

1. **每轮都 `callEvent`** —— 这会重跑**所有**模块的调度处理器：AutoArmor（全量护甲评估）、ChestStealer（全容器扫描 + 规划）、InvManager、Offhand、Replenish……ChestAura 连开 20 个箱子时，这个量直接爆炸。
2. **`cycles = 0` 让上限完全失效** —— 只要每一轮都有动作执行，计数器就被清零，`cycles > 100` 永远不成立。所谓「安全阀」形同虚设，理论上可以无限跑。

而单次评估的代价也不小：`findBestArmorPieces` 做 `repeat(2)` 两轮 pass，每轮对每类护甲做 `maxWithOrNull`，
每次比较要走到 `ArmorPiece.toughness` → `itemStack.armorToughness` → `getAttributeValue`
→ `attributeModifiers.compute(...)`，即**构建整个属性修饰符栈**。

---

### 修法（两层）

**第一层：把调度器的重规划次数变成真上限**

```kotlin
private const val MAX_REPLANS_PER_TICK = 4

var replans = 0
do {
    replans++
    if (replans > MAX_REPLANS_PER_TICK) {
        logger.debug("InventoryManager re-planned ... times this tick, deferring the rest")
        break
    }
    ...
} while (schedule.isNotEmpty())
```

并且**删掉所有 `cycles = 0`** —— 计数器不可重置，上限才真的生效。超出的工作推迟到下一 tick，
玩家完全无感（一 tick = 50 ms），但一帧不会再花几秒。

**第二层：给护甲评估加缓存**

```kotlin
private data class CacheKey(
    val slots, val threshold, val mode, val projectiles, val inventoryVersion
)

val key = CacheKey(slots, durabilityThreshold, mode, considerProjectileProtection, inventoryVersion())
cachedResult?.let { if (it.key.matches(key)) return it.result }
```

版本号用 `menu.stateId`：

- 游戏在**每次槽位变动**（点击、resync、手持切换）时自增它，粒度正好；
- 是 int 读取，**不能**用 `inventory.hashCode()` —— 那是 O(槽位) 且要对每个 ItemStack 做哈希，
  恰恰是这个缓存要消除的开销。

`slots` 用**同一性**比较而非内容比较（所有调用方都传共享的 `Slots.All`），O(1)。

几个必须注意的点（都已处理）：

- `currentBestPieces` 是就地修改的 `EnumMap`，**不能直接塞进缓存**，否则调用方会看到后续修改。
  改为存 `unmodifiableMap` 快照。
- 快照通过 `Collections.unmodifiableMap` 返回，防止调用方改坏缓存。
- 加上 `inventory.selectedSlot` 进 key，因为它会翻转 `isReachableByHand`，比较器对该项排序。

---

### 效果量化

用原型复刻开销模型（41 槽位 / 10 件护甲 / 2 轮 pass）：

| | 耗时 |
|---|---|
| 旧行为 100 轮 | 0.253 ms |
| 新行为 4 轮 | 0.012 ms |
| 调用次数 | **减少 25 倍** |

注意这是**低估**：真实每次比较要进 `getComponents()` + AttributeModifier 栈，单轮成本远高于模型中的
0.0025 ms，所以真实收益比这更大。再加上缓存让同 tick 内的重复调用几乎零成本。

### 改动文件

- `…/utils/inventory/InventoryManager.kt` — `MAX_REPLANS_PER_TICK = 4`，移除全部 `cycles = 0`
- `…/utils/item/armor/ArmorEvaluation.kt` — 按 `stateId` 版本化的评估缓存

### 追踪器的验证

042 装的 FreezeTracker 在本轮完成了它的使命：**在没有崩溃栈的情况下直接指出了卡死的确切代码行**
（`ArmorEvaluation.kt:44` 与 `ArmorComparator.kt:165`）。建议保持开启，后续这类问题可以继续用它定位。
