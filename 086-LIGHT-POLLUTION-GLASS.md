# 086 — Moonlight removal / 光污染玻璃 (source candidate)

## 状态与开启方式

这是在完整 085 源码包上增量制作的 **086 源码候选版**，不是已通过游戏/Android 实机验收的成品，也不是截图 100% 像素复刻认证。

- ClickGUI → Style → **光污染玻璃 / LightPollutionGlass**。
- 启用 ModernGUI → Style → **光污染玻璃 / LightPollutionGlass**。
- HUD 同步由 ModernGUI → Targets → ClientHudCards / VanillaHud 控制；屏幕由 ScreensAndMenus 等原有 Targets 控制。
- 两处 Style 独立选择；旧配置与默认风格不被强制覆盖。Modern / Glass、Glass+Monet / LiquidGlass / MonetCard / CustomCard 均保留。
- ClickGUI 原有 GlassBlur / GlassTintOpacity / GlassRefraction / GlassRim / GlassShadow 也可用于新样式。新样式固定中性玻璃色与局部粉色控件强调，不用主题蓝色染整块玻璃。
- FBO 分配失败时退化为灰色透明卡片与细边，不会冒充已经有真实模糊/折射。

## 本次改动

1. 删除 `ModuleMoonlightKillAura.kt`，移除模块注册、NeoAura 互斥对象与名称、游戏测试中的正向注册检查及中英描述/中文 UI 名称。`src` 下不再有该模块引用。历史版本说明与历史验证 fixtures 不伪造重写。
2. 新建独立 `light_pollution_glass.fsh` 与渲染管线，复用既有背景捕获/模糊和 SDF 顶点传输。每个渲染状态显式选择材质，不靠全局 shader 状态污染已有 Glass。
3. 中性灰透景、低饱和场景采样、边界法线折射、微量色散、窄白边、局部白色光晕和外部阴影。没有在内容上贴任意斜条、彩虹渐变或预制反射贴图。
4. ClickGUI 分类面板、搜索框、顶栏、设置弹层及 HUD 编辑器沿既有玻璃表面入口使用新材质。新风格的分类分隔线/启用标记采用参考图的粉色细线/细竖条，保留原有布局、拖动、滚动与点击处理。
5. 全局已有 ModernGUI 表面入口（容器、组件、提示、聊天、已支持的原版 HUD 区域及原生客户端 HUD 卡片）使用新材质。
6. 独立 Onyx TargetHUD 与音乐岛此前不经 `UiDraw.fillCssBox`，此次在其背景绘制处显式接入，保持头像、歌词、血条、内容动画与输入逻辑不变。音乐界面的变形外壳、迷你播放器与二维码弹窗卡片也显式接入 ScreensAndMenus 目标；二维码本体仍保留不透明底保证可扫。
7. 084 Android 原生 GUI 光标、背包/箱子功能、FPS 刀模与动作、NeoKillAura / NeoKillAura2 的其余代码不在本次改动范围。没有重新加入软件鼠标层。085 战斗移植的已披露缺项未在此次偷偷宣称完成。

## 图片依据及尚未完成的验收

用户的六张参考图：`Screenshot_20261127_232004/232005/232008/232010/232021/232027.jpg`。前几张含相同画面的截图/相册缩放，不能当成六组独立材质测量。

可确定的特征是：
- HUD 卡片不是纯色不透明矩形，能透出背景，外部有低对比软阴影。
- 高光是局部、弯曲的白色边光/反射，右端和圆角处更明显；不是整片高饱和彩虹。
- 最后一张分类面板为灰色透景长栏、细亮轮廓、低对比标题层，粉色出现在控件与启用标记。

**仍有差异/未知：**未取得这些截图对应客户端的原始材质参数，未做同世界、同相机、同 GUI 尺寸的截图差分；HUD 示例中的复杂内部焦散、边角外侧亮弧及动态光源行为不能仅靠静态图确定。当前独立材质只实现边界法线折射与局部边光，不宣称已复现全部内部焦散。现有模块排布、字体、功能组件结构没有替换成另一客户端；第三方自绘 GUI 不保证自动覆盖。因而本版不能标为“完全复刻已完成”。

## 验证证据

- `scripts/verify086/compile-results.txt`：两个完整生产 Java GUI record、三个完整生产 Kotlin 表面发射器对实际 MC 26.3 / RenderPearl / JOML 类型隔离编译；原项目三条 GUI access-widener 通过临时 overlay 应用。项目服务的 compile hosts 在 `hosts.py` 明示，不是全项目编译。
- 同次验证编译精确抽取的 Onyx 适配方法和 Style 枚举；14,008 条生产 record 顶点模式/透明度/退化尺寸断言。
- `scripts/verify086/shader-results.txt`：旧/新生产 GLSL 在 EGL llvmpipe 上编译链接通过，653 条离屏数值断言通过。`synthetic-shader-test.png` 只是合成场景内部测试图，不是游戏截图或视觉验收证据。
- `scripts/verify086/neo-results.txt`：保留六个 NeoAura 完整生产源文件和精确集成片段的实际 API 隔离编译，368 个 API 引用核验、104 项设置检查、67,460 条原生产纯逻辑断言通过。不是上游全部行为的证明。
- `scripts/verify086/cursor-results.txt`：084 原生光标回归再次通过；Android 宿主 fixtures 不是 Android 硬件测试。
- `scripts/verify086/scope-audit.json`：相对不可变 085 的完整文件级差异与打包范围。旧 Glass shader、原生光标实现与无关源文件字节不变。
- 仓库通用 `verify-i18n.mjs` 仍有大量继承的全语言缺项，不报告为全绿；本次新中文术语与 JSON 解析另行验证。

**未通过/未执行：完整 Gradle 编译、Mixin 启动、Minecraft 会话、Android GPU/输入实机、同场景截图对照。** 历史完整 Gradle 尝试的资源与依赖阻塞见 085 文档；本次没有将失败尝试包装成成功。
