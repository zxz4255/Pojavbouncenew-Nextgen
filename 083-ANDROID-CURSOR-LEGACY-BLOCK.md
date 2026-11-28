# 083 — Android GUI 光标强制显示 / 跨 1.8 手动持剑格挡兜底

## 用户报告与修复边界

- Android 上打开游戏 GUI 时要求强制显示鼠标，跨协议版本也一样。
- 跨到 1.8 进服打一局后，手动右键防砍失效。用户已确认表现是 **完全不举剑**，不只是减伤感觉异常，也不是仅自动格挡模块失效。

本包在完整 082 上修改，保留配置与原有模块入口，不改刀模动作或其他模块设置。

## 1. Android GUI 光标

26.3 使用的是 **SDL**，不能套用旧版 GLFW_CURSOR_NORMAL 补丁。检查官方 26.3 客户端字节码确认：

- `InputConstants.releaseMouse()` 只 warp 指针并关闭 SDL 相对模式，没有显式调用 `SDL_ShowCursor()`。
- `MouseHandler.releaseMouse()` 在 Java 侧认为已经释放时会直接返回。因而单纯再调用它，无法恢复 Java/平台鼠标状态不一致的情况。

新增 `AndroidGuiCursor`，不依赖服务器协议或 ViaFabricPlus：

1. 安装新 Screen 后和每帧 GUI 提取前，检查真实当前 Screen。
2. 有可见 GUI 时释放 Java mouse grab；若 SDL 相对模式仍开着，再关闭它；显式请求 `SDL_ShowCursor()`。
3. GUI 尚在时拦截 `grabMouse()`，防止被意外重新锁定、关闭 GUI 或施加原版用于关闭屏幕的攻击冷却。
4. Android 原生/启动器后端可能报告光标可见却不实际绘制，所以在 GUI 最后层绘制黑边白色指针兜底，坐标使用与原版点击一致的窗口→GUI缩放，不受 ClickGUI CSS 动画变换影响。
5. 不每帧 warp 指针、不每帧重置按键，也不使用 `setScreen()` 递归实现。
6. 返回无 GUI 的游戏后不再阻止原版抓取，回到正常转视角。

覆盖原版背包、箱子、聊天、暂停、设置、主菜单及客户端/Via 版本选择等普通可见 Screen。桌面系统保持原行为。Android 识别包括 ANDROID_ROOT、os.version/java.vendor/java.runtime.name 中的 Android 标识和 POJAV_NATIVEDIR。

**保留有意隐藏屏幕的功能**：NoGui、SilentScreen、Opai 替换箱子屏幕仍保留它们原先的 gameplay/转视角语义；不强行把隐藏容器变成可见界面。可见设置屏幕不会仅因旧的 SilentScreen 状态而被排除。客户端绘制的兜底指针不等于能够控制 Android 启动器或操作系统在游戏窗口之外的鼠标策略；若启动器同时绘制自己的指针，也可能看到两者。

## 2. 跨 1.8 的手动持剑使用路径

检查实际 MC/VFP 代码：现代普通剑本身不具有旧版格挡行为；正常情况下由跨协议转换补上 `BLOCKS_ATTACKS`。若当前剑缺少这个组件，`Item.use()` 返回 PASS、使用时长为 0、动画 NONE。

没有用户服务器抓包/真机复现，**不能断言组件缺失就是用户这一局后的唯一根因**。083 针对此类失效增加每次实时判定的恢复路径，而不是只补假举剑动画或周期性乱发格挡包：

- 仅限真实活动的 **协议47（1.8.x）** PLAY 连接；同时核验实际 channel 的目标版本和连接 serverProtocolVersion。
- 拒绝单人游戏、断开/关闭中的连接、LOGIN/CONFIGURATION 状态以及其他协议。
- 不缓存 player、world、connection、当前剑或格挡开关；重生、换局、服务器后端切换后的下一次查询重新检查。
- 只识别 1.8 原有五种剑的物品身份，不依赖可能被服务器更新的剑标签；不把食物、弓、盾、空手、下界合金剑或现代协议变成旧版格挡。
- 当前剑已有 BLOCKS_ATTACKS、CONSUMABLE、EQUIPPABLE 或 KINETIC_WEAPON 时完全保留原有使用行为。
- 缺组件的合格剑，在主手/本地玩家/当前世界的 `Item.use()` 开始真实 `startUsingItem`，使用时长 72000、动画 BLOCK。
- 沿用正常 `MultiPlayerGameMode` 预测和 UseItem → Via 翻译发包链路，**不绕过它发送 raw 1.8 包**，不修改物品组件/NBT，不改已有自动格挡模式。
- ViaFabricPlus 是可选依赖；隔离 API 引用，不在 Item 查询中初始化或缓存 ProtocolUtil 的启动回调结果；暂时不可用的 API 不会把后续查询永久锁成 false。

服务器是否接受格挡、实际伤害与减伤仍由服务器规则决定；本包不承诺在未实测服务器上防御必定有效。

## 修改范围

基线：已交付 `Pojavbouncenew-Nextgen-082-CLICKGUI-REGEX-FIX.zip`

SHA256：`26b08ebf9ea877f2504852da4183ad76bb069a7ac99545ad152cf99fcc3ce1a2`

已有生产文件仅改：

- `injection/mixins/minecraft/client/MixinGui.java`
- `injection/mixins/minecraft/client/MixinMouseHandler.java`
- `injection/mixins/minecraft/item/MixinItem.java`

新增生产文件：`utils/input/AndroidGuiCursor.java`、`utils/item/LegacySwordBlocking.java`。上述三个 Mixin 已在原配置注册，不新增 Mixin 列表项。其他新增只在 `scripts/verify083/` 与本说明。其余 082 源码与配置逐字节保留，未重新加入已删除的拟态样式，也未回退 082 Regex 修复或 081 音乐/在线取钥。

## 本次实际验证

- 新增两个完整 helper、完整 MixinItem 及原样提取的新增光标 hook 类体，使用 **真实官方 MC26.3 / SDL / VFP5.1.1 / Mixin API** javac 编译通过。项目侧无关服务使用测试替身；该编译阶段没有用 Minecraft/SDL/VFP 替身替代真实 API。
- 实际 MC 字节码 **77 项审计通过**：7 个注入目标的名称/参数/回调类型、applyCursor 唯一调用与 graphics 局部变量存活范围、原版 SDL release 行为。此审计不等于完整 Mixin 引擎应用。
- 上一步真实 API 编译出的生产字节码 **不重新编译**，置换显式运行宿主进行逻辑测试：**7085 项 Android/持剑断言 + 2 项桌面断言通过**。包括 1000 次模拟新玩家/世界/连接/剑的换局、失联/协议切换、组件优先、物品过滤、主副手、鼠标重抓拦截、相对模式失配修复、绘制坐标及矩阵恢复。
- 独立 JVM 移除可选 Via 门面类：1000 次持剑查询不启用兜底，Android GUI 恢复仍正常，无可选依赖崩溃。
- 原 082 的 10044 项 Regex/ClickGUI 断言重新通过。
- 原 081 音乐套件重新通过：120 cipher、24 protocol、18 manager/parser、8 Island 状态/80 次禁用循环、35 KGG integration、144 online-key 离线断言及接线/固定上游 JS 检查。
- ZIP 严格对照完整082：只允许上述三个已有文件变动；每一个基线文件必须存在；压缩包 CRC、平根目录及解压字节逐一核验。

**没有进行完整 Gradle 构建、完整 Mixin 运行应用、真实 SDL/Android 指针绘制、真实服务器打一局、实际网络翻译/减伤或真实音乐账号测试。** 模拟换局测试不冒充在服务器上完成1000局。可复现命令、日志和依赖哈希见 `scripts/verify083/`。

## 建议真机验收

1. 备份现有配置，用本完整源码正常编译，替换旧 JAR；不要同时装两个客户端 JAR，无需删配置。
2. 在本版本以及跨到 1.8 后分别打开背包/箱子/聊天/暂停/ClickGUI，检查鼠标持续可见、能移动点击；关闭后可正常转视角。
3. 手持剑在空处按住右键、松开、切剑再试；不要只看 FPS 伪动作。先关闭会接管交互的自动战斗功能以便隔离问题。
4. 同一服务器完成一局、死亡/重生、进入下一局后再次测试；必要时让同服玩家观察举剑并在服务器允许的环境验证伤害。
5. 若仍完全不举剑，请提供当时 latest.log、ViaFabricPlus 实际版本、服务器/游戏模式，以及剑是否原版/自定义。那可能是输入状态、其他模块取消交互或服务器自定义行为，不能只凭当前报告定位。
