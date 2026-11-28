# 028 — MoonLight 移植与 Hello Screen

这是基于 025 的**完整工程源码包**，不是已经验证可用于所有服务器的成品。已移除 SamsaraTargetHUD 及其专属纹理；保留其他原有 HUD。

## 使用范围

- 目标客户端：Minecraft 26.3、Fabric Loader 0.19.5；构建需要 JDK 25。Combat 中新增 `AutoGap` 和独立的 `MoonlightKillAura`。
- 这两个 MoonLight 模块要求 ViaFabricPlus 5.1.1，并且**当前服务器目标协议必须是 1.8（protocol 47）**。其他协议不会尝试用本适配器发送旧包。不要同时启用 `MoonlightKillAura` 和原有的 LiquidBounce `KillAura`。
- 这是源码移植和兼容适配，**尚不能认定为 MoonLight 1.8 原版的完整、逐项等价复刻**。部分转向、目标/移动交互和时序使用了新版客户端的对应机制；真实服务器和 Android 设备上的效果还需要测试。

## AutoGap

原版选项保持：Delay 1000 ms、Health 15 HP、StopMoveWhenEating 关、AlwaysAttack 关、CloseWhenNoGoldenApple 开、LagWhenInAir 关。进度条显示 Time 和百分比，其计数以缓存的移动 tick 为基础。

本次额外提供并默认开启 `WholeInventory` 和 `AutoEnableWithApple`：在已连接 1.8 且处于生存/冒险模式时，检测到普通或附魔金苹果就会自动开启模块；低于设定血量时，如果快捷栏没有苹果，会尝试把主背包或个人合成格里的苹果移到快捷栏。优先使用空格；快捷栏全满时与一个非当前手持槽交换。也会检测盔甲槽、副手和光标中的苹果，但为了避免破坏物品或误操作，**不会从这些位置自动搬运**；打开其他容器或光标携带物品时，自动补货会暂停。关掉 `AutoEnableWithApple` 可恢复手动启停。

协议路径：按原版逻辑缓存动作包、每三 tick 释放一个 tick 的队列，计数到 32 后发送 1.8 C09 选槽、C08 空中使用、重放队列、C09 恢复槽位；补货打开背包使用 C16，点击/关闭通过 ViaFabricPlus 翻译为 C0E/C0D。只有需要切换槽位时才发 C09。物品通过 ViaFabricPlus 转换；金苹果元数据和 NBT 的二进制编码已用单元测试覆盖，但真实服务器接收、背包确认及 Netty 跨编码路径的实际顺序尚未验证。

`AlwaysAttack` 启用后允许 Aura 攻击包绕过吃苹果队列。`MoonlightKillAura` 的旧版交互/剑使用动作在吃苹果时也进入同一队列，完整释放时按原版丢弃 C08、保留 C02。与其他改写移动包、背包或 Blink 的模块共同使用时需要另行测试。

## MoonlightKillAura

根据提供的 MoonLight `KillAura.java` 移植了目标模式与排序、目标和过滤选项、范围、瞄点、转向平滑与随机、APS、Movement Fix、Perfect Hit、Ray Cast、Hit Select、AutoBlock 及暂停选项。菜单名称和新版客户端内部事件不一定与 1.8 原版完全相同，选项存在不等于所有行为已经实服验证。

- 原版给 AutoBlock 声明了不在列表中的默认值 `Fake`，其 `ModeValue` 实际会回退到第一项 `None`；这里也以 `None` 为默认。需要实际剑格挡时可选择 Vanilla、Interact、Watchdog 等模式。
- 仅连接 1.8 时，原版 `Via=true` 分支不会发送剑 C08；要尝试 C08 格挡请使用 `Via=false`。C02 交互及剑 C08 走 1.8 原始包适配；攻击、挥手、释放等由 ViaFabricPlus 转换。
- 原版提供 `Server Test` 范围选项，但其距离分支中匹配的是未列出的 `Server`，因而该项在原版会回退为 0；这里保留这一行为。通常请先使用 `Client`。
- `NoBedNuker` 使用本项目的 Fucker 模块作为对应，Scaffold 和旋转事件采用本项目机制；空中 Hit Select、射线/碰撞、Blink 和旋转平滑等仍可能与原版不同。

所给 MoonLight 原包中没有 ArmourBreak；按要求暂缓该项。项目原有的 ArmorBreak 不是 MoonLight ArmourBreak，本包没有把它冒充为移植结果。NoC03 按后续决定没有新增。

## Hello Screen（Web 标题动画）

启动画面现在是 Web 标题路由里的动画，不再是原生 `HelloScreen`。旧的原生 `HelloScreen.kt` 和 `MixinGui` 旧 hook 已删除；`ScreenManager` 不再为标题挂载原生 Hello，标题直接进入 Web 路由。桌面端（无 Android WebView）回退到原版标题画面；Android 端在 WebView 中渲染同一动画。

动画素材来自用户提供的两张图片：第一张拆成大小两层（大字 `hello_main`、小字 `hello_subtitle`），基于线条骨架生成逐笔显现帧，先左向右画大字，再画右下小字，保留蓝紫、粉橙渐变；第二张照片裁出真实笔迹作为署名 `credit.png`，不使用字体转写。笔迹是透明的，叠加在实际标题画面上，透明处可看到标题和全景；这不是操作系统窗口透明。Web 端用 reveal/delta mask 在原尺寸上合成平滑笔迹（膨胀 mask + source-over 差量），避免逐帧缩放的抖动；大字 1650 ms、小字 1000 ms、停留 250 ms、淡出 350 ms，各 48 帧。笔画路径是从静态图片估算的，不是原作者真实运笔轨迹。标题 Web 组件已挂载后，会短暂等待原生标题/Splash 状态同步；为避免 Android 首次启动时原生状态迟到导致永久空白，状态轮询最多 8 秒、单次 REST 请求最多 1.5 秒，之后以已挂载的 Web 标题为准继续。Hello 设置 API 最多等待 5 秒；请求失败/超时采用默认开启、隐藏构建号的回退，接口明确返回关闭时仍尊重关闭状态。点击可穿透跳过。

设置在 ClickGUI 的 `HelloScreen` 模块里：`ShowBuild` 控制是否显示构建号，`Build` 为只读、不可持久化，由 `GitHubBuildInfo` 读取 CI 注入的 `runNumber`（GitHub Actions 构建号），本地或非 CI 构建显示 `#unknown`。REST 接口 `GET /api/v1/client/hello-screen` 返回 `animationEnabled`、`showBuild`、`build`；`enabled` 取自模块的 `enabled` 状态。主题通过模拟 REST 的 Playwright 测试覆盖动画时序、透明、字幕先后、点击穿透、署名、只读 Build 和 ClickGUI 设置。

原始透明笔迹、逐帧和差量图集及生成脚本 `scripts/generate_hello_ink.py`、`scripts/generate_hello_atlas.py` 一并保留；普通构建不需要运行 Python 脚本。可使用 `python3 -m unittest discover -s src/test/python -p 'test_hello_atlas.py' -v` 检查素材透明度、逐帧单调性和插值合成；检查需要 Pillow 和 NumPy，重新生成还需要 SciPy、OpenCV 和 scikit-image。

## 构建及已做验证

```sh
# 首次构建需要下载 Gradle、Java/Fabric 依赖与主题的 Node 依赖
bash ./gradlew build

# 本次针对性测试（主题由 npm 单独构建后，跳过 Gradle 的 buildTheme）
bash ./gradlew test --tests '*MoonlightPacketQueueTest' \
  --tests '*MoonlightLegacyPacketsTest' --tests '*MoonlightBurstOrderTest' \
  --tests '*MoonlightApplePlannerTest' --tests '*MoonlightAuraMathTest' \
  --tests '*MoonlightNettyOrderTest' -x buildTheme

# Web Hello 动画（模拟 REST + Chromium）
cd src-theme && npm ci && npm run build && npm run check && npm run test:hello
```

- JDK 25 下源码编译和 24 项针对性单元测试通过（ApplePlanner 3、AuraMath 3、BurstOrder 5、LegacyPackets 5、PacketQueue 6、NettyOrder 2），覆盖队列、旧包字段、桩传输调用顺序、选槽与旋转数学，以及真实 Netty LocalChannel 上游戏线程与 event loop 两种提交路径的包顺序；Netty 测试的翻译器和物品转换是 stub，不证明完整线上路径。桩测试不是实服测试。
- `src-theme` 的 `npm run build` 与 `npm run check` 通过（406 模块、0 错误 0 警告）；`npm run test:hello` 在 Chromium + 模拟 REST 下通过 splash 等待、原生 splash 状态卡住时的有界回退、透明、逐字推进、字幕先后、点击穿透、署名、只读 Build、ClickGUI 设置、禁用动画和 800×400 检查。模拟 REST 不等于真实后端或 Android 真机联调。
- Hello 图集的 6 项 Python 检查通过，覆盖透明像素、逐帧笔迹不回退、最终图与彩色笔迹一致、相邻帧透明合成插值，以及原/平滑笔迹轮廓 IoU（main 0.993、subtitle 0.990）。
- Xvfb 桌面环境下运行 `HelloScreenGameTest` 冒烟测试：检查 AutoGap、MoonlightKillAura、HelloScreen 注册、标题路由识别、Hello 开关、构建号格式，并在 interop 启用时真实调用 `hello-screen`、`module/HelloScreen`、`modules/settings`、`modules/toggle` 接口（修改 ShowBuild、尝试伪造 Build、切换模块开关并恢复）。测试时显式跳过 Android 专用 DroidBridge；桌面回退不验证 Web 动画或 Android WebView。13 项游戏测试全部通过编译，但并未全部运行。
- CI 构建号注入已用模拟 `GITHUB_ACTIONS=true GITHUB_RUN_NUMBER=987654` 验证 `processResources` 写入 `runNumber=987654`，并恢复本地空构建号显示 `#unknown`；真实 GitHub Actions 未验证，`987654` 仅为模拟值。
- 尚未在真实 1.8 服务器上验证吃苹果、受击打断、自动补货、攻击、格挡、反作弊兼容性或两个模块同时运行；不保证可以通过反作弊，也不保证库存永不回滚。Android 真机上的 WebView 画面、帧率和启动时序尚未验证。

## 来源与许可

`AutoGap.java`、`KillAura.java`、`RotationUtils.java` 参考 [MoonLight](https://github.com/randomguy3725/MoonLight)，原作者包括 Randumbguy、wxdbie、opZywl、MukjepScarlet、lucas、eonian；MoonLight 原包采用 GPL-3.0。项目根目录 `LICENSE` 保留 GPL 许可。进度条 Inter 字体的 SIL OFL 1.1 许可位于 `src/main/resources/assets/liquidbounce/font/INTER-LICENSE.txt`。Hello 字迹源自用户提供的图片，不使用 Apple 字体或 Apple 图形素材。
