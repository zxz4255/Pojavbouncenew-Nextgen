# 070 — FPS Knife 重构与 ClickGUI 修复

## 交付内容

这是基于 069 的完整源码压缩包，不是可直接放入 mods 的编译 JAR。
ZIP 根目录直接包含 `src/`、`gradlew`、`build.gradle.kts`，没有额外的项目目录套层。
不包含 `.git`、Gradle 缓存或构建产物，保留 069 的其余源码。

## FPS Knife

- 13 款程序化立体刀模：Nexus Skeleton、Karambit、Talon、Butterfly、M9 Bayonet、Skeleton Dagger、Flip Knife、Huntsman、Bowie、Kukri、Katana Tanto、Stiletto、Kunai。
- 根据玩家手臂变换计算手掌握持点；刀模以握持点缩放，检视转环使用独立枢轴。抛刀动作允许有意离手。
- `Viewmodel > BladeTiltAngle` 默认 22°，设置范围 0°–30°，以水平线为基准进行透视补偿。攻击／检视时有意旋转刀身；该设置不是对所有动画帧屏幕角度的限制。
- 攻击实体事件或非挖掘攻击键按下沿触发挥砍；不再依赖通用挥手动画，避免开箱、丢物品等误触发。
- `AttackTrigger` 可选攻击触发或仅实体攻击事件触发。`HitEntityOnly` 不代表服务器确认伤害。
- 默认仅静止待机时检视，移动、格挡、挖掘或攻击会中止检视；默认静止 1.8 秒自动检视，设为 0 可关闭自动检视，V 键手动触发。
- 空手拳击：刺拳、直拳、摆拳、上勾拳组合，抱架格挡及待机热身。
- 1.8 风格右键剑格挡与左右键防砍动画：TacticalParry、Classic1_8、SpinDeflect。
- 集成 SwordBlock 与盾牌视觉处理，保留原版副手物品时不额外绘制重复辅助手。
- **格挡、拳击与刀模均为客户端视觉效果，不会自行赋予服务器端减伤、额外伤害或更远攻击距离。**

主要设置分组：

- 顶层：Model、Skin、Engraving、Weapons、EmptyHandBoxing、DualHands。
- Animation：AttackTrigger、InspectStyle、InspectKey、InspectOnlyWhenIdle、AutoInspectIdle、Block18Animation、Block18Style、RightClickSwordGuard、DrawOnSwitch、AnimationSpeed、CameraSway。
- Viewmodel：BladeTiltAngle、Scale、HandSpread、OffsetX/Y/Z、NeonGlow。

## ClickGUI

- SDL3 输入法预编辑文字显示与文本焦点同步。
- Ctrl+F 聚焦搜索、× 清空，中英文／空格英文／缩写／别名检索与相关性排序。
- 语言切换时重新排序模块：英文忽略大小写，中文使用中文 Collator；保持现有展开状态。
- 面板可视高度与滚动上限联动，含小视口边界处理；只对视口内的展开行路由设置输入。
- 内嵌列表到达滚动边界后允许外层面板继续滚动。
- 支持面板滚动条轨道／滑块拖动，以及模块列表区域纵向拖动滚动。

## 本地化

更新 `en_us.json`、`zh_cn.json`、`zh_cn_ui.json`；移除旧 FPS Knife 设置描述，补充新设置、刀型与动作名称。已检查 JSON 无重复键，并补充中文字体字符表。

## 验证状态与限制

已使用 JDK 25、Kotlin 2.4.20、官方 Minecraft 26.3 client.jar 进行**独立组件编译与无窗口回归检查**：

- FPS Knife 四个 Kotlin 源文件与两个 Java 手部渲染 mixin 编译通过。
- ClickGUI 主视图、屏幕、文本编辑器、设置浮层与列表控件编译通过。
- `UiDraw` 的修改文字输入绘制方法通过检查；未在独立检查中编译整个渲染子系统。
- 13 款刀模，共 1,500 个四边形，检查顶点和法线数值有限。
- 54,990 次姿态快照数值检查、78 组握持枢轴缩放／镜像检查。
- 检视待机门控及移动中止检查通过。
- 搜索、EN→ZH→EN 实时排序与 16 组可视高度边界检查通过。

**这些检查使用了隔离依赖夹具，不等同于完整项目构建。** 完整 Gradle 构建在此环境中因内存资源限制未完成；尚未完成 Minecraft 游戏内启动、Mixin 实际应用、GPU 画面、Android 触控和系统输入法实机验证。不能据此承诺无运行时问题。

检查脚本、测试夹具及结果位于 `scripts/verify070/`，运行方式见该目录 README。

## 建议实机验收

1. 使用项目所需的 Java 25 环境运行 `bash gradlew build`。
2. 分别测试中文输入法和英文键盘，搜索、清空、切换语言；确保候选窗口与输入焦点正常。
3. 缩小窗口、展开很长的设置面板；滚轮、列表边界传递、滚动条及触摸纵向拖动均检查。
4. 逐一切换 13 款刀模，检查右／左惯用手与 0°、22°、30°，并调整 Scale。
5. 对比空挥、攻击实体、持续挖掘、开箱、丢弃物品、移动中按 V 与静止自动检视。
6. 空手左右键连招、持剑右键及左右键防砍，配合 SwordBlock 和副手盾牌／其他物品检查。
