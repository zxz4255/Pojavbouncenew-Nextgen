# 047 深度学习原生化（方案备忘，未实施）

> 状态：**仅调研与方案设计，代码零改动**。记录于 2026-11-21。
> 起因：041 轮曾误判 AI 平滑是音频问题的根因（045 已证伪），但顺带发现了 `deeplearn` 在 Android 上的真实状况。

---

## 一、现状

`src/main/kotlin/net/ccbluex/liquidbounce/deeplearn/` 共 8 文件 / 812 行，依赖 DJL 0.38.0 + PyTorch CPU engine：

```kotlin
jij(libs.djl.api)       // ai.djl:api:0.38.0
jij(libs.djl.pytorch)   // ai.djl.pytorch:pytorch-engine:0.38.0
```

`LiquidBounce.prepareGuiStage` 里目前是：

```kotlin
DeepLearningEngine.markUnavailable()
logger.info("Deep learning disabled in the native-only build.")
```

即 **AI 平滑功能被硬关掉**，`AiAngleSmooth.process()` 走进降级分支，落回 `fallback` 平滑器并弹一次错误提示。功能不算崩，但「AI」这个卖点是死的。

## 二、关键发现：模型极小

我解开了内置模型 `21kc11kp.params` / `19kc8kp.params` 的二进制格式（`DJL@` magic，v1，format=`tf`）：

```
DJL@  00 00 00 01   tf   FLOAT32   data0   [tensors...]
```

每个张量自描述：`tag[8]` + `u16 长度前缀名字` + `u32 count` + `u32[] dims` + FLOAT32 负载。

解出的网络结构（4 层 MLP）：

| 层 | 形状 | 参数量 |
|---|---|---|
| Dense 1 | 6 → 128 | 896 |
| BatchNorm 1 | 128 | 512 |
| Dense 2 | 128 → 64 | 8,256 |
| BatchNorm 2 | 64 | 256 |
| Dense 3 | 64 → 32 | 2,080 |
| BatchNorm 3 | 32 | 128 |
| Dense 4 | 32 → 2 | 66 |
| **合计** | | **12,194** |

- 纯权重 **48,776 字节**，文件 **50,775 字节**（头部开销 1,999 字节）。
- 输入 6 维、输出 2 维，与 `CombatSample.INPUT_SIZE = 6` / `OUTPUT_SIZE = 2` 完全吻合：

```
输入：totalDelta.yaw, totalDelta.pitch, prevVelocityDelta.yaw, prevVelocityDelta.pitch,
      speed(targetDiff.horizontalDistance + playerDiff.horizontalDistance), distance
输出：velocityDelta.x, velocityDelta.y   // 下一帧旋转增量
```

**结论：这个规模完全不需要 DJL。** 12k 参数的推理是纯算术，一次前向约 20 微秒。

## 三、方案（若将来要实施）

### 3.1 自带 `.params` 解析器（约 150 行）

按上面解出的格式读 `InputStream` → `Map<String, FloatArray>` + 维度表。无第三方依赖。

### 3.2 手写前向传播（约 80 行）

```
x = input                                          // 6
for each (linear, bn, relu) triple:
    y = x · Wᵀ + b                                 // Linear
    y = (y - runningMean) / sqrt(runningVar + eps) * gamma + beta   // BatchNorm 推理模式
    x = relu(y)
out = x · W₄ᵀ + b₄                                 // 最后一层不加激活
```

参数文件里 `gamma`/`beta`/`runningMean`/`runningVar` 齐全，BatchNorm 可直接用于推理模式。

### 3.3 训练怎么办

`ModelWrapper.train()` 目前用 DJL 的 `EasyTrain` + `Adam(1e-3)` + `l2Loss`。手写训练比推理复杂得多（反向传播 + Adam 状态），但**这是可选的**：

- **只做推理**：`AiAngleSmooth` 能用，`CommandModels` 的训练命令降级为「不支持」。
- **连训练一起做**：再写 200 行左右的 autograd。工作量翻倍，但模块仍是 12k 参数级，可行。

建议先只做推理，训练留成独立工作。

### 3.4 依赖清理

- `build.gradle.kts:184-185` 删 `jij(libs.djl.api)` / `jij(libs.djl.pytorch)`
- `gradle/libs.versions.toml` 删 `djl` 版本条目与 4 个 library 定义
- `src/main/java/.../injection/mixins/djl/MixinUtils.java` 整文件删除（它的唯一作用是给 DJL 的任务进度打 hook）
- `deeplearn/models/ModelWrapper.kt`、`DeepLearningEngine.kt` 重写
- `build.gradle.kts:121` 的 `clientGameTest` 里 `deeplearning.engines` 系统属性一并删除

### 3.5 收益

**这大概率就是 Android 上深度学习「不可用」的真实原因。** DJL 的 PyTorch CPU engine 在运行时才去下载对应平台的 native 库，Android/ARM64 通常拿不到可用的 `.so`，`Engine.getInstance()` 直接失败。改成纯 Kotlin 后：

- 无需任何 native 库
- APK 少掉几十 MB 的潜在引擎下载
- 冷启动不再有引擎初始化等待

## 四、验证方式（沙箱可做）

1. 用 Python 按同一格式解开 `.params`，numpy 实现前向传播。
2. 构造一组固定输入，与 Kotlin 实现的输出逐位比对（容差 1e-6）。
3. 交叉验证：拿真实的游戏帧数据无法做，但数值层面足以证明解析器和前向正确。

**沙箱无法验证的**：真机推理耗时、与 `AiAngleSmooth` 的实际手感。

## 五、不做的事

- 不改 `AiAngleSmooth` 的调用点（一行都不用动）。
- 不改 `CombatSample` 的特征定义（模型已按此训练，改了输入维度就废）。
- 不动 marketplace 的模型分发逻辑。

## 六、当前决定

**2026-11-21：暂不实施。** `LiquidBounce.prepareGuiStage` 保持 `markUnavailable()`，AI 平滑继续走 fallback。待需要时按本文档启动。
