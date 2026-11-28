# 082 — ClickGUI Regex 类型崩溃修复

## 对应报告

Minecraft 26.3 / LiquidBounce 0.41.1 / Android Pojav 或 Zalith，在打开或渲染 `TabbedClickGuiScreen` 时：

```
java.lang.ClassCastException: kotlin.text.Regex cannot be cast to java.lang.String
  at TextSettingNode.<init>(SettingWidgets.kt:629)
  at SettingRenderer.createNode(SettingRenderer.kt:91)
```

这次问题是设置值类型处理错误，不是根据该异常就能归因的显卡、内存或 Android 兼容性问题。

`ValueGroup.regex()` 与 `text()` 均使用 `ValueType.TEXT`。原渲染器把全部 TEXT 强制当作 `Value<String>`；泛型擦除使第一次 cast 未报错，读取 `Value<Regex>.get()` 时才触发 String 检查并崩溃。动画遍历也会创建节点，因此即使没有编辑正则也可触发。现存的 RegisterRegex / LoginRegex / TitleFilter 均属于这种设置。

## 修复

- 不再把 TEXT 一律转换为 `Value<String>`。
- 普通字符串仍按字符串显示、编辑和提交；不 trim，不按正则校验。
- Regex 显示 `.pattern`，编辑成功后写回真正的 `Regex`，保留当前 Regex 的选项（如 IGNORE_CASE / MULTILINE），不写入 String。
- 捕获模式语法异常；无效或未完成的正则仅留在输入框草稿中，不进入 Value.set、监听器或配置。
- 无效草稿聚焦期间显示红边与中英提示；Enter 不会存入无效值；失焦/Escape 后恢复最后有效模式。原来“有效输入即时应用”的行为保持不变，不把 Escape 改成全部撤销。
- 空正则合法，允许删除为 `""`。
- 对未知的 TEXT 载荷采用已有只读 fallback，不强行改写其类型。
- 同时修正正则列表的新增路径：用 `innerType == Regex::class.java` 判定，包括空列表。无效模式不插入，不再经由返回 String 的 TEXT deserializer 污染 Regex 集合。普通字符串列表保留原有 trim 行为。

## 兼容性及范围

以云端已交付 081 完整 ZIP 为唯一基线，先校验 SHA256：

`4ef8b17ad9ec340ce9ca506434db7b1c70ff3a5512e972db15d2b7ff1d55fc12`

不改设置名、ValueType 枚举、JSON schema、模块入口、配置工厂或 Gson adapter；**无需删除/重置已有配置**。本包是完整源码，不是可直接放进 mods 的成品 JAR。

已有文件仅修改以下三个：

1. `ui/clickgui/setting/SettingRenderer.kt`
2. `ui/clickgui/setting/SettingWidgets.kt`
3. `ui/clickgui/setting/ListSettingWidgets.kt`

新增生产文件：同目录 `TextSettingCodec.kt`。其余新增仅为本说明及 `scripts/verify082/` 测试/审计文件。所有其他 081 文件逐字节保留，包括 081 在线取钥、079 解密、080 Neo 编译修复、现有刀模动作、背包/箱子模块、音乐 UI 等；不重新加入已删除的拟态样式。字节相同仅证明没有改动，并不等于重新验证所有既有功能或保真要求。

## 本次实际测试

`verify082` 使用 JDK 25 / Kotlin 2.4.20：

- **081 负对照**：从已交付 ZIP 提取原类，实际调用 `SettingRenderer.stepGroupAnimations`，复现 Regex → String 的 ClassCastException。
- **081 列表负对照**：复现向空 Regex 列表错误加入 String。
- **082：10,044 项断言通过**，含 2,000 次打开/编辑循环。
- 编译执行实际 `Value`（含监听/事件）、实际 `UiTextEditState`、完整实际 `SettingRenderer`、原样提取的实际 `SettingNode` / `TextSettingNode` / `MutableListSettingNode` 类体、实际 ListValue / MutableListValue 类体和新的完整 codec。
- 验证初始化/动画/缓存/渲染路径、鼠标聚焦、字符输入、删除、Enter、失焦、Escape、无效草稿保持、最后有效值、正则选项、空模式、外部更新/restore、普通文本、未知 TEXT、正则/字符串列表的类型与写入。
- 游戏/绘制/窗口/翻译等宿主以及无关设置控件为显式测试 fixtures；这不是完整 Minecraft 或 GPU 渲染测试，也不把宿主 fixtures 当成实际 MC API 编译通过的证明。

重新运行原 `scripts/verify081/run.sh`，通过：

- 实际固定上游 JS 的 standard/lite 请求向量、源码完整性及接线检查。
- 120 项 KGG cipher、24 项协议、18 项 manager/parser、8 种 Island 状态及 80 次禁用循环、35 项 KGG 集成、144 项在线取钥离线断言。
- 其中在线接口为拦截/合成响应，下载含本地 loopback；没有声称验证真实账号、真实在线取钥成功率、原生音频播放。

日志及复现说明在 `scripts/verify082/`。**未执行完整 Gradle 游戏构建，未在用户 Android/Adreno/Zalith 设备上实测。**

## 建议验收

1. 备份现有配置，正常编译此完整源码；替换旧客户端 JAR，不要同时放入两个版本。
2. 保留原有配置打开 Tabbed ClickGUI，多次切分类、展开 AutoAccount / Debug。
3. 编辑 Regex：输入 `[`、`(` 时应仅提示无效、不崩溃；补全后即时生效。
4. 关闭再打开 GUI、重启客户端，核对有效模式持久化；普通密码/文本框不得被当成正则校验。
