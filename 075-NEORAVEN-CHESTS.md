# 075 — NeoRaven ChestAura / ChestStealer 独立移植

以已交付的 074 完整源码为基线，新增 **NeoChestAura / NeoChestStealer**。这不是替换旧模块，也不是复用旧 Stealer 的筛选/队列。原 ChestAura、ChestStealer、FPSKnife 和 074 Cinematic/Rain 的受保护源码与 074 逐字节一致。

上游：Matr1ze/NeoRaven，固定提交 `b483ffc576e335e01f2914c920e88289ab090e66`，Forge 1.8.9。目标：本工程 MC 26.3 / LiquidBounce 原生事件、配置、容器、旋转与 UI。许可证/原始文件及哈希见 `licenses/neoraven/CHESTS.md`、`scripts/verify075/upstream/`。

## 使用

在 **Player** 分类或搜索中找：
- `NeoChestAura` / `Neo自动开箱`：自动选择、瞄准并打开箱子。
- `NeoChestStealer` / `Neo自动拿箱`：自动取走箱内物品，可以单独用于手动开箱。

两者可一起启用。先关闭旧 ChestAura 和旧 ChestStealer（包括旧 Stealer 内的 Aura 功能）。若同时启用，对应旧模块优先，新的引擎让步，避免同一箱子被两套逻辑操作。

默认附近玩家检测为 **5 米**，有人靠近时 Aura 暂停；设为 **0** 关闭该检测。战斗/其他模块占用旋转时，本模块低优先级让步。穿墙开箱能否成功取决于服务端；这里不绕过服务端验证。

如需现有拿箱 HUD，另开工程已有的 Opai Dynamic Island；此版本只接入其拿箱观察接口，不移植整个 NeoRaven Watermark。

## 原版设置与默认值

### NeoChestAura：5 项

| 设置 | 默认 | 范围/含义 |
|---|---|---|
| Range | 3 m | 0–10，步进 0.1 |
| ThroughWalls | false | 允许使用原版的穿墙目标/备用命中点 |
| PlayerCheckRange | 5 m | 0–20，步进 0.1；0 关闭 |
| Rotation | true | 原生旋转仲裁，低优先级 |
| MoveFix | Silent | Silent / Strict / None |

独立箱子扫描/历史，普通箱和陷阱箱；最近可用目标选择、遮挡/可开启性/空箱标记检查；横向相邻同类型箱子合并标记。80 ms 目标稳定时间、关箱后 >100 ms 冷却、>1200 ms 等待超时。发送交互后等待内容确认；世界或玩家实例变化时隔离历史，单纯开关模块保留已开箱记录。瞄准沿用方块坐标 `.45 + face * .5`，备用命中点保留面约束随机位置。Silent / Strict / None 映射宿主移动修正。

### NeoChestStealer：15 项

| 设置 | 默认 |
|---|---|
| Instant | false |
| AutoClose | true |
| IgnoreTrash | true |
| ChestIntegrity | true |
| PickArmor / PickBlocks / PickFood / PickProjectiles / PickSword | true |
| PickBow / PickPotions / PickTools | false |
| StartDelay / MinDelay / MaxDelay | 各 50 ms，0–1000，步进 10 |

保留分类筛选、与背包/已装备物品比较、箱内同分保留和背包同分排除规则；盔甲、剑、弓、工具的原版评分，工具耐久门槛、方块黑名单、金苹果压制普通食物、重复鱼竿排除。IgnoreTrash=false 取全部。普通模式使用独立固定槽位队列、起始延迟、每 tick 一次快移、随机点击间隔和结束等待。Instant 在完整内容应用后一次有界批量处理所有非空槽，忽略分类/垃圾筛选和延迟，但仍遵守 ChestIntegrity；不将刚打开但尚未同步的空壳菜单当作完成。

ChestIntegrity 保留 Ender/Team/Island/末影/队伍标题拦截，以及本模块所需的 Hypixel 大厅/排队侧栏判定（排除 Replay/Practice/Pit）。容器身份、世界/玩家切换、同一快照重复处理、批量中途关箱、自动关闭及 HUD 替换界面会话均有独立状态管理。

## 跨版本适配边界（不是声称 1.8.9 逐包完全相同）

1. **原版设置、筛选与开箱/取物状态逻辑已按固定源码实现**；但现代物品采用 tags/components，额外物品、数据包属性和新版本药水没有对应的 1.8 类。实际组件读取/资源重载仍需游戏内验证。
2. 原 C08/S30/S2F/S32 改为 MC 26.3 原生 useItemOn、容器 QUICK_MOVE、完整内容 TAIL 回调；Aura 先确认，再允许 Stealer Instant。**没有照搬 1.8 的 S32 确认包和五秒旧 S2F 镜像改写**，现代 state-id/服务端重同步保持权威，不伪造成功、不直接篡改背包。Via/1.8 服务端兼容性待实测。
3. 交互观察器放在 Connection.send 原始 RETURN，设计为跳过 HEAD 取消的调用；与其他 Mixin、Blink/队列实际注入顺序未在游戏内验证，不能将“发送函数返回”理解为服务器已接受。
4. 旋转使用宿主 RotationManager；已检查其 1-tick 请求过期后 `activeRotationTarget` 回退 `previousRotationTarget` 的语义，POST 阶段核对本模块请求身份；未实测服务器旋转、移动修正与其他战斗模块竞争。
5. UI 使用工程原生配置与中英文文本，Loot 面板接现有 Opai 宿主，不是完整 Watermark 的外观重制。

## 已执行验证

`JAVA_HOME=... KOTLINC=... MC_JAR=... VERIFY_LIBS=... bash scripts/verify075/run.sh`

- 12 个固定上游 Java 文件及设置哈希；20 个旧开箱/拿箱、刀模、074 特效文件保护校验。
- 新模块、策略、现代物品/协议适配器与实际修改的 OpaiChestLoot Kotlin 针对真实 MC 26.3 API 编译；两份 Java Mixin 编译。周围 LiquidBounce 配置/事件/旋转/HUD 绘制依赖使用夹具，不是全工程构建。
- 从原上游 Java 原样提取筛选方法为 oracle；16,384 个随机 27/54 槽箱子，覆盖全部 1024 个筛选开关组合，选槽列表一致。物品/评分输入为测试模型，不是完整 1.8 运行环境。
- 边界/状态测试：评分与同分规则、延迟、标题/大厅判定，Aura 80/100/1200 ms、确认与历史隔离，普通队列/Instant 54 次有界处理、空壳防护、重复同步、AutoClose、替换屏幕、会话切换/中途关闭。
- 中英文 JSON 和重复键、注册、回调顺序静态检查。
- 完整执行已有 **070–074 针对性回归链**，含刀模/GUI 状态和 074 GLSL/Mesa EGL 合成图对照。

日志：`scripts/verify075/results.txt`。工具要求及夹具边界：`scripts/verify075/README.md`。

**未完成/不作通过声明：完整 Gradle 构建、真正应用 Mixin、MC 服务器开箱事务、原生物品组件运行时、实机旋转/移动修正、Android/Pojav、UI/外观完全一致。** 当前交付为完整平铺源码 ZIP，不是已编译或实机认证的 JAR。

## 建议实机验收

1. 关闭旧模块，仅开 NeoChestStealer，分别试普通/大箱、背包接近满、同分装备、金苹果、鱼竿、各分类与 IgnoreTrash。
2. 试 StartDelay/MinDelay/MaxDelay 和 AutoClose=false；Instant 开启后检查空壳等待、54 槽批量、服务端回滚和手动中断。
3. 两模块同开，试 3 m 内普通/陷阱双箱、遮挡、附近玩家检测=0/5、三种 MoveFix、切世界/重生及旋转竞争。
4. 在受控服务器检查取消包/Blink、延迟、回滚、Via 协议；再试 Opai 面板、EN/ZH、Pojav。
