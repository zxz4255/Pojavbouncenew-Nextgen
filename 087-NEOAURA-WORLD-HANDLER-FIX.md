# 087 — NeoAura world event-handler name collision fix

## 修复

用户 CI 在 `compileKotlin` 报告：

- `ModuleNeoKillAura.kt:98`：`world` hides member of supertype `MinecraftShortcuts`。
- `ModuleNeoKillAura2.kt:61`：相同问题。

真实继承关系是 `ModuleNeoKillAura[2] → NeoAuraModule → ClientModule → MinecraftShortcuts`，而 `MinecraftShortcuts.world` 是返回 `ClientLevel` 的世界快捷属性。两个模块的 `private val world` 实际上存储 `handler<WorldChangeEvent>` 返回的事件钩子，不能用 `override` 把它冒充世界属性。

两处仅将事件钩子的字段名改为 **`worldChangeHandler`**，保留事件类型、回调内容、初始化时机和世界切换清理逻辑。仓库内这两个旧字段没有额外读取点，因此没有其他业务引用要改。

相对完整 086，生产代码只有上述两行变更。086 的 Moonlight 删除、光污染玻璃、HUD 接入、旧样式、原生鼠标及其余功能字节保持不变。本包仍为源码包，不是编译好的 mod JAR。

## 修正验证盲点

此前 `scripts/verify085/fixtures.py` 的 `ClientModule` 编译宿主没有实现 `MinecraftShortcuts`，所以它不能发现真实继承链上的同名属性冲突。之前的隔离编译通过不代表完整项目编译成功，本次不掩盖这个漏检。

新增 `scripts/verify087/`，保留历史验证文件原样：

1. 复用 085 的已明示项目服务宿主，但让 `ClientModule` 宿主实现 **完整、真实的生产 `MinecraftShortcuts.kt`**，同时编译真实 `AddonApi.kt`。仅 `utils.client.inGame` 等全局服务仍为明示宿主。
2. 校验不可变 086 ZIP 的 SHA-256，提取其中原始六个 NeoAura 源文件，使用增强后的相同编译环境。
3. **负向对照：原始 086 恰好复现用户的两条 `world` / `MinecraftShortcuts` 编译错误**，没有把其他错误误当成预期失败。
4. 使用当前修复后的六个完整 NeoAura 源文件，同样包含真实接口，隔离编译通过。
5. 104 项设置检查、368 个实际 MC/VFP API 引用核验、67,460 条原生产纯逻辑断言通过。

详见 `scripts/verify087/results.txt`。`scope-audit.json` 记录相对 086 的逐文件范围；打包脚本验证两处业务源文件各只改了一行，所有其他原始文件均逐字节不变。

## 仍然不能据此声称的结果

此次没有完成整个 Gradle 构建、Mixin 启动、游戏/Android 实机测试；只确认所报告的两处冲突已在包含真实继承接口的编译检查中修复。086 玻璃的视觉/实机验收限制和 085 战斗移植未完成项保持原披露，不因本次编译修复而变为已完成。
