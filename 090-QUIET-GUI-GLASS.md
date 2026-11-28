# 090 — GUI 玻璃去高光、小圆角、内部 Gaussian Blur

## 本次按新要求覆盖 089 的 GUI/PANEL 外观

只调整当前 **LightPollutionGlass / 光污染玻璃** 的 GUI 材质；不再将 089 的长条高光作为 GUI 验收目标。

- **去高光**：GUI 卡片不再绘制顶部乳白高光、右上角回折、亮边、反射带、外弧、halo 或装饰发光线；取消 GUI 边缘折射/色散。
- **小圆角**：表面发射器统一将 GUI 卡片的现有圆角上限收至 3 个 GUI 单位；原本为直角的拼接边仍保持直角。不是胶囊形或大圆角面板。
- **内部模糊**：复用现有 H/V 可分离 Gaussian blur 的四次离屏处理，卡片内部直接采样模糊后的实时背景；只在卡片圆角轮廓内显示，不将整个屏幕或卡片外缘变糊。
- **外围淡阴影**：只输出一层黑色、单一 Gaussian falloff 阴影；最大输入 alpha 为 28/255，spread 上限 6 个 GUI 单位。没有白色溢光、双重 elevation、亮描边。
- **均匀底色**：GUI 卡片使用轻微中性底色，不再叠加顶部亮、底部暗的材质渐变。两处额外的分类／全局设置页头 glaze 也在本样式下停用。
- **展开设置**：取消本样式设置内槽的白边，仅保留很淡的内槽底色与小圆角。
- **覆盖范围**：ClickGUI、全局 ModernGUI 菜单表面、音乐 GUI 的外壳/弹窗/卡片。功能性文字、开关、选中行指示不被删除。

## HUD 保持原样

TARGET、COUNTER、SOFT 三类 HUD shader 分支仍保留 089 的反射效果。Onyx `hudCard=false` 的音乐 GUI 改走安静的 PANEL；`hudCard=true` 的音乐 Island 与 TargetHUD 仍使用原有 profile。

在相同离屏输入下，对比不可变 089 shader 的 84 组 HUD 样本（body/shadow/rim/arc、不同尺寸/透明度/折射）最大 RGBA 数值差为 **0**。这只是限定输入下的 shader 回归，不是游戏截图一致性证明。

## 使用与限制

继续使用原有的 **LightPollutionGlass** 样式，无新增开关。GUI 上的 Rim/Refraction 即使有旧配置值，也不会重新引入材质高光；这些设置对 HUD 的原有效果仍保留。

内部 blur 仍使用原有 BlurStrength / GlassBlur 强度与帧缓冲捕获时机。本次没有改共享 blur kernel、捕获调度或 HUD 模糊。没有可用 framebuffer 时仍只能使用无真实模糊的半透明回退；不会把普通透明填充冒充 Gaussian blur。

这是完整工程**源码 ZIP，不是编译好的 mod JAR**。尚未完成完整 Gradle、Mixin 引导、Minecraft/Pojav/Android 实机视觉验收。`scripts/verify090/offscreen-quiet-gui.png` 是带合成背景的真实 shader 离屏样本，不是游戏截图。

## 实际检查与保留

- 2 个完整生产 Java 表面记录、3 个完整 Kotlin 表面发射器在实际 MC26.3 API 类型下隔离编译通过；Onyx 方法、HUD 升级/作用域方法、两处新增页头 guard/call 片段也编译通过。项目 config/theme 等仍使用明确 hosts；不是完整 UI 文件编译。
- 177,024 个生产 Java 顶点断言和 27,006 个生产作用域／路由片段断言通过。
- 95 个 EGL shader 检查通过：GUI 平坦背景不出现局部高光、旧高光参数不生效、GUI rim/arc pass 无输出、阴影 RGB 为黑且有界、blur 常量保持/高频抑制/轮廓内覆盖，以及上述 84 组 HUD 数值对比。
- 实际运行原有 Gaussian H/V fragment 的四次处理；测试用简单全屏三角形作载体，不代替真实游戏帧缓冲调度验收。
- 相比 SHA256 为 `7944467014ce6c1086e42c62f48047032e7adde86a9fa36d485276668ea85686` 的不可变 089，仅修改 6 个 GUI 材质/调用文件，其余 2720 个基线文件字节不变，0 删除。
- 088 MVP、087 修复、原生鼠标、刀模与整理器等逻辑不动。**此前 NeoInvManager 开关回退仍待失败日志定位；本次不是该问题的修复包。**

复现与限制详见 `scripts/verify090/README.md`；旧 089 对照页属于历史版本，当前 GUI 外观以本说明及 090 样本为准。
