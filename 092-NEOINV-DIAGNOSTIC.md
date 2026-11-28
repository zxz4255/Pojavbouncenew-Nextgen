# 092 · NeoInvManager 追踪诊断版（不是已确认修复版）

你选择了直接制作追踪诊断版。本版用于抓到“一开就关”的实际写入来源，不会强制保持开启，也不声称已经彻底修复。

## 先确认实际运行的是本版

这是完整源码 ZIP，不是可以直接放入 mods 的 JAR。请按你原先的编译/部署方式替换运行文件。

在 ClickGUI 查看 NeoInvManager 的模块说明/提示：必须出现 **092-DIAGNOSTIC 追踪诊断版**。开启后 HUD 标签带 `092 ·`。如果看不到标记，先不要重复测试——实际运行的可能仍然是旧构建。本版说明还会显示当前 enabled、整理状态及最后一条诊断摘要。

## 请这样抓一次

1. 进入世界，打开 ClickGUI，确认上述 092 标记。
2. 按你原先能复现的方式开启 NeoInvManager **一次**，看到回弹后停手，不要连续点很多次。
3. 在世界中等约 **5～10 秒**，让始终活动的诊断监听器记录关闭后的真实状态。
4. 上传当前 Minecraft 实例游戏目录中的：
   - **`logs/neo-invmanager-092.log`**；
   - 如果有 `logs/neo-invmanager-092.previous.log`，一起上传；
   - 最好同时附上本次 `logs/latest.log`，便于对照原始异常。
5. 专用日志无法创建时，所有记录仍尝试镜像到 `latest.log`，搜索 `[NeoInvManager/092]`。日志达到条数上限后，重启游戏可开始新的捕获，不需要删除配置。

Pojav 不同实例的游戏目录可能不同；请找这次实际启动实例的 `logs` 文件夹，不要找源码 ZIP 所在目录。

## 能区分哪些情况

- `REQUEST`：有人尝试写 Enabled，附完整调用栈和唯一写入编号。
- `UNCHANGED`：重复写入相同值，没有触发生命周期；仍记录来源。
- `TRANSFORMED`：onChange 链完成，记录原始请求、变换后的候选值。
- `IMMUTABLE` / `REJECTED`：写入被不可变规则或异常阻止；异常原文/栈保留。
- `APPLY_FAILED`：赋值提交器出错，保留原本的异常传播行为。
- `COMMITTED`：实际配置字段已经提交，记录 actual 值。
- `COMPLETED`：原有后置通知链及 StateFlow 发布完成。
- `OBSERVER_FAILED`：字段已经提交，但后置事件/监听器出错；不把它误报成“启用从未成功”。
- `LIFECYCLE_ENABLE` / `LIFECYCLE_DISABLE`：包括直接调用生命周期而没有经过 Value.set 的情况。
- `INPUT_PRESS` / `INPUT_RELEASE`：命中的 Neo 绑定来自键盘还是鼠标；不记录无关键盘输入。
- `HANDOFF` / `STATUS` / `RUNTIME_PAUSE` / `WORLD_CHANGE`：整理控制权交接、暂停及换世界情况。
- `STATE_SNAPSHOT`：发生过开关写入后，每约 5 秒记录配置 actual、发布 flow、运行条件 running 和 GUI 类型；**即使 Neo 已关闭，诊断监听器仍活动**。

### 不要把正常的中间态当作回弹

原项目的启用生命周期/ModuleToggleEvent 在字段提交前调用。因此 `LIFECYCLE_ENABLE storedBeforeCommit=false` 和刚提交时 `COMMITTED actual=true flow=false` 都可能是正常中间态，必须结合相同 write 编号的 COMPLETED/失败记录及后续请求判断。

如果先出现 COMMITTED true、随后 REQUEST false，就能从后者的调用栈定位关闭来源；如果 REQUEST true 后直接 REJECTED，则按该异常修复；如果 actual 始终 true 而界面显示关，就继续查界面呈现或运行状态，而不是再强改 enabled。

## 本版改动范围

- `Value` 增加可选写入观察器；**只有 NeoInvManager.Enabled 安装观察器**。原先的值变换、赋值、事件、StateFlow 顺序、异常吞入/传播行为均刻意保留。
- 新增布尔写入诊断事件、Neo 专用日志写入器和常驻快照监听器；Neo 生命周期/热键只增加诊断记录和版本标记。
- 专用日志单文件约 2MiB 轮换，保留一个 previous 文件；每次游戏运行最多 2048 条记录后停止输出，避免长期刷盘。
- 不主动采集背包物品内容、聊天、账号或服务器信息；异常原文/栈可能带本地路径，分享前可以检查。
- 不改背包规划/原生点击安全策略，不解除战斗/Blink/光标等暂停，不强制开关，也不删除你的配置。
- 保留 091 MotionBlur、090 玻璃/HUD、光标、刀模、MVP、Aura、箱子模块；这些源码逐字节不变。

## 验证与尚未完成的部分

`verify092` 这次使用**完整的生产 Value、ToggleableValueGroup、Toggleable、ClientModule、MinecraftShortcuts、NeoInvManager/Hotkeys 以及安全会话/策略**联编，并运行实际的 delegated Enabled setter，不再用一个普通 Boolean 的假 ClientModule 替代启用链路。ValueGroup 的设置 DSL、事件/语言/日志/配置/按键服务及旧模块仍是明确列出的宿主；MC API 来自官方 26.3。

6148 项断言通过，覆盖真实启用/关闭链、成功/拒绝/变换/后置失败分类、外部关闭来源、故障诊断消费者不阻止赋值、trace/untraced 状态一致、关闭后的独立快照、专用文件/镜像/轮换/上限/写盘失败。

这仍不是完整 Gradle 构建、完整 Mixin 或 Pojav 真机运行；**未复现你的设备故障，也尚未拿到它的根因**。等你回传这次日志后，才能针对真实来源继续修复，不会把诊断包冒充最终修复包。
