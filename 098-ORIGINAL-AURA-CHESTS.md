# 098 — 清理额外 KillAura，恢复原版 LiquidBounce ChestStealer / Aura

## 本次范围

以已交付 097 源码 ZIP 为唯一基线，SHA256：
`a82137f4177fcb2426f0bf7e5d0869e3d3be7fdea454bb714640b34bd14794a8`。

- 删除 NeoKillAura、NeoKillAura2 的实际模块及六个实现文件；清除注册、SwordBlock、CombatManager、两个目标 HUD 的引用。
- 删除已无消费者的旧 Moonlight2 转头/1.8 包辅助文件；没有保留隐藏的另一套 KillAura。
- 原版 `combat/killaura/` 整个目录逐字节保留。原版 TpAura、CrystalAura 不属于额外 KillAura 变体，本次保留。
- 删除 NeoChestAura、NeoChestStealer、NeoChestEnvironment，以及专用的两个网络 Mixin 和注册。
- 恢复官方 `ChestStealer`，及其内部 `Aura`、`SilentScreen`。按用户选择，没有独立的 ChestAura 模块/开关。
- StorageESP 和现有灵动岛拿箱面板改为读取原版状态。恢复静默箱子界面的渲染/点击/鼠标钩子，保留 NoGui 与 Android 原生光标路径。

## 上游来源与恢复程度

仓库：<https://github.com/CCBlueX/LiquidBounce>

固定提交：`7a729e78aab726a0bc2c2721ef29c5718053dc80`。

选择与本工程 `ScheduleInventoryActionEvent` 相匹配、在上游 inventory-plan 重构之前的版本；不把新版库存引擎整套移植进来，也不覆盖 096 的库存安全修复。该提交的 `InventoryValueGroups.kt` 与本地逐字节相同。

原始源码及库存 API 参考保存在 `references/liquidbounce098/`；每个文件的上游 SHA256 在 `scripts/verify098/source-lock.json`。

- `FeatureChestAura.kt`：与该提交逐字节一致，包含原版选箱、转头、墙距、手动交互记录、等待界面/重试和双箱记录。
- `FeatureSilentScreen.kt`：与该提交逐字节一致，包含原版隐藏界面、解锁鼠标和箱内物品标签。
- `ModuleChestStealer.kt`：除一个给现有灵动岛传递候选槽位的**只读观察调用**外，与该提交逐字节一致。去掉这个调用块后，审计严格验证整份源码相等。
- 不调用 Neo 开箱策略，不保留 Neo 的开箱计时/协议引擎，不用原版名称包裹 Neo 实现。拿取筛选、QuickSwaps、排序、QuickMove/DragAndDrop、满背包处理、AutoClose 均来自上述原版源码和本地原有库存调度器。

历史 `licenses/`、`references/`、旧版说明和旧验证脚本是来源/历史记录，不注册也不编译为生产模块。旧版 aura/chest 测试引用已删除实现，不再作为 098 的运行入口；使用 `scripts/verify098/`。

## 保留项

097 的 LivingEntity 归属击杀 MVP；096 的后台整理/护甲交换修复；NeoInvManager 的开关迁移、执行状态与安全策略；095 歌词、阴影、音乐 UI；MotionBlur 和原生 Android 光标均保留。

`NeoChestItems.kt` / `NeoChestPolicy.kt` 名称虽带 Chest，但 NeoInvManager 仍直接依赖其物品分类、评分类型和随机间隔函数，所以保留原字节，避免误删背包整理依赖。这两个文件不是开箱模块、没有注册和包监听；恢复的原版 ChestStealer/Aura 不导入它们。

## 使用与设置变化

1. 只使用原来的 **KillAura**；Neo 的两个模块不再出现。
2. 启用 **ChestStealer / 自动拿箱**，其内部 **Aura / 自动开箱** 默认开启，与固定上游相同。
3. 自动开箱服从原版范围、墙距、战斗暂停、旋转和重试设置；不会绕开服务器距离/交互检查。
4. 原版 `CheckScreenHandlerType`、`CheckScreenTitle` 决定可拿取容器；菜单标题/类型不匹配时不会被灵动岛强行隐藏。
5. `SilentScreen` 默认关闭。启用后使用原版隐藏界面及 `UnlockCursor` 选项；现有 Opai 拿箱面板仍是独立的显示观察者，不执行物品操作。
6. NeoChestAura/NeoChestStealer 的旧配置不自动映射到原版参数，以免把不同语义的配置误套进来。没有主动删除用户配置文件。
7. **原版 ChestStealer 含丢弃逻辑**：例如原版默认 `OnFull=Throw`，以及使用 InventoryCleaner 时的快捷交换流程。本次忠实恢复上游，没有把 NeoInvManager 的“不自动丢弃”规则套在原版拿箱模块上；请在不重要的物品/测试环境中核对设置。NeoInvManager 自己的丢弃白名单保护未变。

## 已执行验证及边界

- 固定基线 SHA256、上游六份参考哈希、三份恢复源码等价检查。
- 注册与残留检查：生产源码/资源不存在已删除 Neo aura/chest 实现、导入和键名；只有原版 KillAura 注册；ChestStealer 只有一个注册，Aura 为子功能。
- 原版三个完整 chest Kotlin 文件、实际 OpaiChestLoot、实际 MinecraftShortcuts 对真实 MC26.3 客户端 API 定向编译。
- 对编译后的原版 chest 类检查 50 个真实 MC 成员引用。
- 执行原版 Index / Distance（默认参数）/ Random 排序方法：6000 组确定性生成槽位列表，2,140,019 个断言。配置/槽位服务为显式测试宿主；不是物品点击或游戏测试。
- 五个修改后的 Java 钩子方法体原样提取、完整 AndroidGuiCursor，与真实 MC26.3 / SDL / Mixin callback API 和上述恢复 Kotlin 类一起编译。未应用 Mixin。
- 重跑 NeoInvManager 的实际生命周期/设置迁移编译回归和 096 模型执行回归，覆盖后台/打开背包、空/满护甲槽、交换守恒、空尾 SWAP 历史复现和丢弃保护。
- 重跑 097 MVP 实际 API/状态及适配器模型测试：51,039 个状态断言、41 个完整适配器断言。
- 重跑手动举剑回归：77,850 个状态断言、24,582 个输入钩子断言与旧错误分支负控。
- 严格 097→098 文件级白名单差异检查；非本次范围的所有基线文件逐字节相同。打包后检查 ZIP CRC、清单和每个条目的字节。

**这是源码交付，不是编译好的 mod JAR。没有完成/声称完整 Gradle 构建、Mixin 应用、服务器交互、实际物品组件行为、Android/Pojav 实机或画面验收。** 定向编译的项目配置/事件/库存/旋转/绘制服务为显式宿主，不能代替完整工程编译和游戏实测。

复现入口及完整日志：`scripts/verify098/README.md`。
