# 085 — NeoRaven KillAura / KillAura2 源码移植候选版

## 交付状态

基于已交付的 **084 完整源码**，加入两个独立模块：

- **NeoKillAura**：原 NeoRaven KillAura 的独立选敌、转头、APS、自动格挡和发包队列实现。
- **NeoKillAura2**：Client 保留 **Expo / Leader / Flux**，默认 Leader，三套配置独立保存。

不是给已有 KillAura/Aura 换名字或套预设。原 KillAura、Aura（Ts2）、MoonlightKillAura 均保留，原配置不迁移、不覆盖；启用其中一个控制器会关闭其他四个，防止同时攻击/转头/格挡。两个现有 TargetHUD 和 CombatManager 可读取新增模块的当前目标。

**重要：这是有验证的源码移植候选版，不是“已通过全工程构建和真机/服务器验证的 100% 逐包等价版”。下文明确列出没有原样复现的上游专属联动。若以“所有 NeoRaven 专有依赖也逐项复刻”为完全移植标准，本版尚未完成该验收，不能把设置数量或离线测试当作完全等价的证明。**

## 已实现的主体

### NeoKillAura

- 原版 29 项功能设置：APS / 受击触发攻击次数、AutoBlock APS / lag、攻击/挥手/格挡三种距离、FOV、平滑、目标数量/切换延迟、四种排序、三种转头方式，以及实体/好友/队友/隐身/鼠标/武器/挖掘/界面/格挡/疾跑门控。
- 签名时间戳目标轮换，与 Aura2 的索引轮换分开；优先攻击距离内目标，否则挥手距离，最后允许仅在格挡距离内持有目标。
- 火球目标及 700ms 攻击间隔门控；不可见点回退、AimClosestPoint、灵敏度量化、Yaw jitter 和转头恢复。
- **Manual / Vanilla / Damage / Fake / Interact A / B / C / D / Swap / Dual** 十种模式。
- Interact A–D 各自的直接释放、切空槽、切槽后释放、来回切槽后释放流程；Swap 的双阶段，Dual 的两把剑按伤害排序/交替槽位和 20Hz 累加器。找不到第二把剑时进入 -2 回退，不伪造第二把剑。
- APS 时间检查与渲染帧事件关联；Dual 累加只在更新事件执行，不会按渲染帧率累加。
- 有界队列、FIFO、lag 百分比累加、停止时丢弃待攻击/挥手/开始挖掘动作，切世界清空旧队列；不把聊天、keepalive、背包、登录/断开包放入战斗队列。
- Fake/packet-block 的视觉状态接入现有 SwordBlock 入口，因此 FPSKnife 的既有格挡判断也能读取；不修改刀模型、动画资源或输入坐标。

### NeoKillAura2

- **Leader：25 项分支设置**。Single/Switch、Distance/Health/HurtTime/FOV、None/Legit/Silent/Lock View、Normal/Nearest/Smart、Move Fix、Min/Max APS、tick 单位切换延迟、实体过滤等。
- **Expo：33 项分支设置**。SINGLE/SWITCH、VIEW/DISTANCE/HEALTH/HURT_TIME/ARMOR、SILENT/NO_RENDER/LOCK/MANUAL/NONE、移动修正、Min/Max APS、ms 单位切换延迟、过滤、方框/头框/受击色/圆环/距离圈和三种颜色来源等。
- **Flux：15 项分支设置**。Single/Switch、Angle/Health/FOV/Range/Armor、Switch Size/Delay、即时/限速可见视角旋转、Yaw/Pitch Offset、实体/隐身过滤和 Auto Disable。
- Client 和 Pause While Blocking 共两项，合计保留 **104 项上游功能设置**；说明行不伪装成功能开关。额外 AimHelpers 组承载上游原本放在全局 Settings/Friends 中的辅助参数和 EnemyNames。
- Flux 坚持普通摄像机、已有原版 EntityHitResult、正常实体交互距离及可见性检查；**不是把配置 Range 直接当成发攻击包距离**。先检查上一渲染帧的鼠标命中，再转相机，保留上游的这一顺序。
- 保留 pinned 源码中容易误读的行为：Leader `Bot Check=true` 实际允许 bot；Leader `Strafe` 的映射和 Expo `NO_RENDER`/`MANUAL` 沿用该版本源码，不根据名称另造算法。

## 明确的版本适配 / 尚未原样复现项

上游为 Forge 1.8.9，本项目为 Minecraft 26.3。这里没有假装导入整个原客户端：

| 项目 | 本版处理 / 限制 |
|---|---|
| 原始 C02/C07/C08/C09 及 Forge PRE/POST/RenderTick | 由本项目攻击/Swing API、RotationManager、现代事件和既有 VFP1.8 bridge 承接。攻击在网络移动 POST 阶段提交，状态 1 的批次显式 flush；**不保证和 1.8 原客户端逐包时序完全一致**。 |
| 1.8 sword-block / 原始切槽包 | 只在实际 VFP legacy PLAY 连接有效时启用 packet-block 分支；现代服务器上不降级为裸 use-item 垃圾包。Manual/普通攻击仍可使用。 |
| 上游 S2F / S30 Hypixel SkyWars 背包同步包抑制 | **未照搬**。现代背包布局/组件不同，继续接受服务端同步，避免错误吞包导致背包不同步。 |
| AutoBlockin、AutoLadder、BedAura、Displace、Hit Select、AntiKnockback delay、AutoGapple、IRC hit 回调 | **没有移植这些完整的 NeoRaven 专属模块/握手。** 本版接本地 Scaffold 让路、RotationManager 优先级、CombatManager 暂停和原生 AttackEntityEvent 取消；这不是逐项等价替代。 |
| Manual 右键交互拦截、换槽/释放与禁用收尾 | 用现代 PlayerInteractItem/Packet 事件及本地槽位复位适配；没有复刻上游全部鼠标事件/热键旁路细节、slot-post 去重与 silent-swing 装备进度重置。 |
| 受击/伤害格挡计时 | 本地有效向上速度包（0 < Y < 0.5625）触发；Damage 的 800ms 计时由该事件供给。上游该文件的 damageBlockAt 没有写入路径，因此这是显式兼容修正，并非严格复现该缺陷。 |
| 设置范围排序 | 实际计算保证 attack <= swing <= block；保存的数值不按上游 GUI 回调交换，避免隐式改写配置。 |
| 分支切换 / 初始视角 | 清理旧目标和待旋转，重新从当前相机起步；分支设置不互相覆盖。不保留上游某些切换时的过期 yaw/index 状态。 |
| Target render | 使用 26.3 drawBox / drawLineStrip 和现有 Onyx Theme，替代旧 GlStateManager；不保证旧 GL 线宽/像素完全相同。 |
| 连接/禁用安全 | 队列上限 256，仅当前会话；不会在切世界后重放旧攻击。新增的安全边界不是上游无界队列的字面复刻。 |

## 保留 084 及既有功能

- 084 原生 Android GUI 光标代码及 hooks **逐字节不变**。无软件箭头、无额外光标渲染层、无坐标重映射或模拟输入；GUI 外保持原游戏抓取逻辑。
- 083 的 1.8 剑格挡 fallback、082 Regex 修复、081 音乐/授权在线 key 路径及所有既有模型/动画/整理/开箱模块均保留。
- 既有源码只扩展 ModuleManager、SwordBlock 视觉判断、两个 TargetHUD 目标来源和 CombatManager 战斗识别，未删除/覆盖旧 Aura 实现或设置。

## 已执行验证

见 `scripts/verify085/results.txt` 和 `cursor084-regression.txt`：

1. 官方 MC26.3 和 VFP5.1.1 文件 SHA256 锁验证。
2. 六个新增完整 Kotlin 生产文件，使用 **Kotlin 2.4.20 / JDK25 / 真实 MC26.3** 及真实编译的生产 VFP bridge 进行 API 编译。项目宿主类是显式 fixtures，不是假称全工程编译。
3. SwordBlock 的三个视觉入口、两个 HUD resolver 和 CombatManager getter 的**原样方法体**同时进行类型编译；不是这些原类的完整构建。
4. **368 个真实 MC / VFP bridge 成员引用**的字节码描述符解析通过。
5. **104 项上游设置**的标签、默认值、范围、步长、选项顺序及读路径核对通过。
6. **67,460 条确定性断言**：原版时间戳选敌差分、Aura2/Flux 切换、十种格挡模式的相位/门控/分支动作、双剑不足回退、队列 FIFO/容量/禁用过滤、lag 百分比、平滑/角步/灵敏度量化。
7. 重跑 084 回归：76 项 API/字节码检查、20,036 条 Android 宿主断言/2,000 个模拟 GUI 周期、4 条桌面宿主断言均通过。它们是显式测试宿主，不是 Android 实机结果。

**未通过/未执行的验证**：全工程 Gradle Kotlin/Java 构建、完整 Mixin 启动、真实 VFP 网络翻译、Minecraft 游戏会话、Pojav/Android 硬件鼠标、真实服务器对局和反作弊兼容性。全工程尝试因资源/依赖阶段未完成；离线尝试在 Loom 下载 Minecraft 配置阶段失败，未进入全工程编译。不提供可运行成品 JAR，也不保证任何服务器可用或不被检测。

## 来源 / 许可证

固定上游：`Matr1ze/NeoRaven@b483ffc576e335e01f2914c920e88289ab090e66`。
原三份 combat 源码及相关辅助代码保留在 `licenses/neoraven/combat-upstream/`（`.java.txt`，不会参与构建），哈希在 `scripts/verify085/source-lock.json`。GPLv3 原文位于 `licenses/neoraven/LICENSE`；归属和适配说明见 `licenses/neoraven/COMBAT.md`。
