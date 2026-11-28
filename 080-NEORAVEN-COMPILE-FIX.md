# 080 — 修复 CI 报告的三个 Kotlin 编译错误

基于已交付 079，源码包，不是已构建 JAR。

## 精确修改

1. `ModuleNeoChestAura.kt`：增加 `import net.ccbluex.liquidbounce.event.EventState`。该枚举定义在 `event/Event.kt` 的父包，`event.events.*` 不会导入它。
2. `ModuleNeoChestStealer.kt`：私有会话世界缓存 `world` 及其使用点改名为 `sessionWorld`。
3. `ModuleNeoInvManager.kt`：同样把私有会话缓存改名为 `sessionWorld`。

后两处不应直接加 override：`MinecraftShortcuts.world` 是返回非空 ClientLevel 的快捷访问器，私有 `Any?` 会话身份缓存具有不同用途。改名保留原有世界/玩家切换检测和重置逻辑。未更改模块设置名、默认值、点击策略、丢弃保护、配置格式或已有功能。

## 为何以前的聚焦测试没有发现

旧 075/076 测试替身把 EventState 放到了错误的 `event.events` 包，且 ClientModule 替身没有继承 MinecraftShortcuts，掩盖了真实宿主集成问题。080 测试在临时目录修正这些替身，并直接编译真实 MinecraftShortcuts 接口；旧测试文件/历史结果不改写。

## 本次验证

`scripts/verify080/results.txt` 记录：
- 用修正后的宿主测试环境，原始 079 三个模块确实复现用户报告的三条错误。
- 修改后三个真实模块在 MC 26.3 官方客户端 API、真实 MinecraftShortcuts 和从真实 Event.kt 提取的正确包枚举下通过聚焦编译。其他宿主模块、配置、事件分发及 HUD 绘制器仍是替身。
- 既有箱子回归通过：16,384 个随机箱子模型，覆盖全部 1,024 种过滤组合，以及 Aura/Stealer 时序、会话和 Instant 边界。
- 既有背包回归通过：240,000 次分类/评分比对，12,000 个随机背包收敛测试，1,296 次装备交换、2,016 次部分堆叠合并及丢弃/回滚保护。
- 对 079 基线审计：仅上述三个生产文件改变；音乐/KGG、Island、刀模、旧箱子模块、其他背包整理、GUI、历史测试和日志保持字节一致。

**未执行完整 Gradle compileKotlin/构建、游戏、Mixin 应用、服务器或 Pojav 测试。** 聚焦编译修复了已复现的这三条错误，不代表整个项目所有构建问题已排除。079 KGG 的真实样本/密钥验证边界不变。

复现见 `scripts/verify080/README.md`，差异与哈希审计见同目录 `scope-audit.json`。
