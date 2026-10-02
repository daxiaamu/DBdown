# Jetpack Compose 玻璃效果复刻指南

本文整理 DBDown 0.6.17 的实际实现，供其他 Android Compose 项目复刻。包含底栏玻璃胶囊、普通玻璃提示卡片、顶部渐变模糊，以及跟手拖动时的处理。实现基于 Haze 1.7.3；下面的参数是本项目当前取值，不代表其他版本库的默认行为。

**最重要的原则：底栏只模糊一次，底栏底色与选中胶囊底色互斥绘制，文字和图标最后绘制。** 胶囊是同一块玻璃上的选中色区域，不是盖在另一块玻璃上的第二层玻璃。

## 最终效果如何分层

```text
清晰的文字和图标                       最上层
┌──────────────────────────────────┐
│ 底栏底色区域 │ 胶囊主题色区域      │   两块区域互斥
│              │ 随页面进度移动    │
├──────────────────────────────────┤
│ 共享的一次背景模糊               │   底栏与胶囊共用
├──────────────────────────────────┤
│ 页面原始内容                     │   Haze 采样源
└──────────────────────────────────┘
圆角裁剪与外侧阴影                     容器装饰
```

只有页面内容注册为 `hazeSource`。底栏、胶囊、提示卡片和标题栏放在采样源之外，避免把已经绘制的玻璃再次当作背景处理。

本方案让底栏和胶囊共享相同的模糊半径。若未来需要两者具有不同模糊强度，不能直接再给胶囊加一层 `hazeEffect`，需要另行设计互斥的模糊合成。

## 当前参数

| 对象 | 参数 | 当前值 |
| --- | --- | --- |
| 普通提示卡片和顶部栏 | 模糊半径 | 14 dp |
| 底栏及选中胶囊 | 模糊半径 | 26 dp |
| 玻璃底色 | 透明混合 alpha | 0.46 |
| 胶囊色 | 颜色混合 | `lerp(primaryContainer, primary, 0.30f)` |
| 玻璃噪点 | noiseFactor | 0 |
| 底栏宽度 | 两个等宽选项及内边距 | 250 dp |
| 每个选项 | 宽度和高度 | 118 × 50 dp |
| 底栏内边距 | 四周 | 7 dp |
| 底栏与卡片 | 阴影 | 4 dp |
| 普通卡片 | 圆角 | 24 dp |
| 底栏按下 | 整体缩放 | 0.96，100 ms |
| 底栏松开 | 弹簧 | dampingRatio 0.8，stiffness 650 |

`alpha = 0.46` 用在染色层，不用在整个组件上。改变模糊半径控制背景细节清晰度；改变染色 alpha 控制颜色遮盖程度；改变主题色混合比例控制选中颜色深浅。这三件事应分开调。

## 接入采样源

在已配置 Jetpack Compose 的 Android 工程中加入项目实际使用的版本：

```kotlin
implementation("dev.chrisbanes.haze:haze:1.7.3")
```

页面骨架如下。`PageContent` 和 `OverlayContent` 是目标项目自己的组件。

```kotlin
val haze = remember { HazeState() }
Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize()
            .hazeSource(haze)
            .background(MaterialTheme.colorScheme.background)
    ) {
        PageContent()
    }
    OverlayContent(haze)
}
```

Modifier 顺序有意义：如果某块纯色背景本身也要被捕获，放在 `hazeSource` 的内侧，如上面的 `.hazeSource(...).background(...)`。仅设置在采样源外侧的背景不能视为已被记录。

原始页面应允许绘制到浮层后面。滚动列表用 `contentPadding` 为首尾内容留空间，避免直接把整个列表裁到玻璃区域之外，否则玻璃背后只有空白，模糊自然不明显。

## 可移植的底栏玻璃容器

以下是从当前实现提取的两选项底栏容器。它只负责外观，`progress` 由外部 Pager 提供，点击和拖动也由外部负责；这样不会把业务状态绑定到玻璃绘制上。

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

@Composable
fun GlassDockSurface(
    haze: HazeState,
    progress: Float,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val capsuleTint = lerp(colors.primaryContainer, colors.primary, 0.30f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val path = remember { Path() }
    val position = progress.coerceIn(0f, 1f)
    val style = HazeStyle(
        backgroundColor = colors.background,
        tint = HazeTint(Color.Transparent), // 染色在下方统一处理
        blurRadius = 26.dp,
        noiseFactor = 0f,
    )

    Box(
        modifier.width(250.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
    ) {
        Box(
            Modifier.matchParentSize()
                .drawWithContent {
                    drawContent() // 先画内侧的 Haze 背景模糊
                    val inset = 7.dp.roundToPx().toFloat()
                    val itemWidth = 118.dp.roundToPx().toFloat()
                    val travel = 118.dp.toPx()
                    val offset = (position * travel).roundToInt().toFloat()
                    val left = inset + if (rtl) travel - offset else offset
                    path.reset()
                    path.addRoundRect(
                        RoundRect(
                            left, inset, left + itemWidth, size.height - inset,
                            CornerRadius((size.height - 2 * inset) / 2),
                        )
                    )
                    // 底栏底色只画在胶囊之外。
                    clipPath(path, ClipOp.Difference) {
                        drawRect(colors.background.copy(alpha = 0.46f))
                    }
                    // 胶囊只画一次主题色，不再附加 background 或 hazeEffect。
                    drawPath(path, capsuleTint.copy(alpha = 0.46f))
                }
                .hazeEffect(haze, style = style)
        )
        Row(
            Modifier.padding(7.dp).height(50.dp).fillMaxWidth(),
            content = content,
        )
    }
}
```

两个选项分别使用 `Modifier.weight(1f).fillMaxHeight()`，内容保持清晰绘制。不要再给选中项加不透明 `Surface`、`background(primaryContainer)` 或额外的模糊层。

这个片段适用于固定两项、250 dp 宽的底栏。若要支持更多选项或自适应宽度，应从实际布局统一计算选项宽度、行程、胶囊位置和遮罩，不能只改外层宽度。

## 跟手移动和动画中断

底栏与页面必须共用一个进度源：

```kotlin
val progress by remember(pager) {
    derivedStateOf {
        (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
    }
}
```

不要只根据 `currentPage` 给胶囊做独立的 `animateFloatAsState`。页面停在半途时，胶囊也应停在对应位置；否则动画会追赶手指。

DBDown 的拖动适配器把胶囊位移转换成 Pager 位移：

```text
换算比例 = (Pager 页面宽度 + 页面间距) / 胶囊行程
Pager 位移 = 手指横向位移 × 换算比例
```

手势在 `pager.scroll(dragPriority)` 内调用 `scrollBy`，复用 Pager 的滚动互斥机制，接管正在进行的动画。完整适配器见 [FloatingTabs.kt](../app/src/main/java/com/daxiaamu/dbdown/FloatingTabs.kt) 中的 `CapsuleDragState`。

- 松手速度超过 180 dp/s 时按方向切页，否则选最近的一页。
- `reverseDirection = rtl`，绘制胶囊时也镜像坐标。
- 底栏的 `startDragImmediately = false`，让动画中途的再次点击仍可触发；实际拖动超过 touch slop 后再接管。
- 点击切页必须取消旧的切页协程，再执行新目标。项目中通过 `LaunchedEffect(vm.tabRequest)` 完成，避免串行排队播放旧动画。
- 文字和图标可按相同进度在未选中色与选中色间插值。

按下效果施加于整个底栏的 `graphicsLayer.scaleX/scaleY`，不要再给每个 tab 添加一套涟漪或悬停底色。触摸监听使用 `PointerEventPass.Initial` 观察按下状态，不消费事件。

## 普通玻璃提示卡片

普通卡片没有移动选中区域，可以直接使用一次模糊与一次 tint：

```kotlin
val style = HazeStyle(
    backgroundColor = MaterialTheme.colorScheme.background,
    tint = HazeTint(MaterialTheme.colorScheme.background.copy(alpha = 0.46f)),
    blurRadius = 14.dp,
    noiseFactor = 0f,
)
Box(modifier.shadow(4.dp, shape).clip(shape)) {
    Box(Modifier.matchParentSize().hazeEffect(haze, style = style))
    content() // 标题、按钮等位于模糊之上
}
```

若再绘制带底色或 elevation 的 `Surface`，可能把玻璃盖住，或让阴影出现在玻璃内部。当前项目的封装是 [Ui.kt](../app/src/main/java/com/daxiaamu/dbdown/Ui.kt) 中的 `GlassPrompt`。

### 提示卡片上滑关闭

拖动阶段使用布局位移 `.offset { IntOffset(...) }`，直接更新位置，不做插值延迟；松手才决定回弹或关闭。

本项目曾同时使用图层平移和整卡 alpha 渐隐，出现阴影异常。改成布局位移、拖动中保持整卡 alpha 后，阴影和背景采样更稳定。这里是本项目的实测结论，并非所有 Compose 图层变换都不能用于玻璃。

当前关闭条件为上滑超过 `min(48 dp, 卡片高度 × 0.4)`，或上滑超过 8 dp 且速度超过 800 dp/s。回弹动画允许新触摸打断；提示内容解析完成时沿用同一事件 key，不重置手指位置。新提示到达时才重置。

完整实现见 [HeadsUpSwipe.kt](../app/src/main/java/com/daxiaamu/dbdown/HeadsUpSwipe.kt)。

## 顶部栏的柔和模糊边界

标题和按钮保持原布局，只将背景模糊层向下延伸 24 dp，再设置强度渐变：

```kotlin
val fadeStart = with(LocalDensity.current) { (topInset - 20.dp).toPx() }
val fadeEnd = with(LocalDensity.current) { (topInset + 24.dp).toPx() }

Box(
    Modifier.fillMaxWidth().height(topInset + 24.dp)
        .hazeEffect(haze, style = glassStyle) {
            progressive = HazeProgressive.verticalGradient(
                startY = fadeStart,
                startIntensity = 1f,
                endY = fadeEnd,
                endIntensity = 0f,
                easing = FastOutSlowInEasing,
            )
        }
)
```

本项目 `topInset = 状态栏高度 + 72 dp`。示例中的 `glassStyle` 指上一节的 14 dp 玻璃样式。标题文字和点击区域不要跟着背景延伸，以免形成看不见的触摸遮挡。

## 常见问题与处理

| 现象 | 优先检查 |
| --- | --- |
| 胶囊变灰、颜色叠加 | 是否同时绘制整条底栏 tint 和胶囊 tint；Haze tint 是否已设为透明 |
| 裁剪后仍有残留 | 是否在裁剪带内部渲染层的模糊输出；最终方案改为只模糊一次、互斥绘制普通底色 |
| 模糊看不出来 | 背后是否只有纯色空白；采样源是否包含实际列表内容 |
| 加深颜色后不透明 | 是否误把整卡 alpha 或 tint alpha 提高；先调整主题色混合比例 |
| 拖动时阴影被截断 | 检查整卡 alpha、裁剪范围和图层变换；阴影在圆角裁剪外侧绘制 |
| 胶囊比页面慢半拍 | 是否使用了两套独立动画，而非直接消费 Pager 连续进度 |
| 移动时遮罩错位 | inset、宽度、位移是否共用同一套几何参数；是否处理 RTL 和像素取整 |
| 暗色下发白 | 是否写死白色底色；使用 `MaterialTheme.colorScheme` 并实测对比度 |

最初尝试让底栏和胶囊分别执行 `hazeEffect`，再裁掉底栏对应区域，用户仍观察到叠加残留。最终实现不再依赖对模糊输出做差集裁剪；差集只用于普通底色。因此，复刻时应从本指南的单次模糊方案开始，不要恢复两层玻璃结构。

## 验证方式

先用有文字、有彩色图标的真实滚动列表观察，然后再做纯色背景像素测试。纯色无法检验模糊强度，但很适合检查重复染色。

当背景色为 S、胶囊色为 T、染色 alpha 为 0.46 时，不考虑色彩空间换算与取整的预期颜色为：

```text
胶囊内部颜色 ≈ 0.54 × S + 0.46 × T
```

分别检查停在左端、中途和右端时的胶囊内部像素。取样点避开文字、圆角边缘与阴影，允许设备渲染的少量色差；不要只验证选中状态变量。

本项目对应测试：

- [TabGlassAppearanceTest.kt](../app/src/androidTest/java/com/daxiaamu/dbdown/TabGlassAppearanceTest.kt)：三个位置的单层染色像素检查。
- [TabGestureTest.kt](../app/src/androidTest/java/com/daxiaamu/dbdown/TabGestureTest.kt)：跟手、回弹、拖动中断、动画中途再次点击和状态同步。
- [HeadsUpAppearanceTest.kt](../app/src/androidTest/java/com/daxiaamu/dbdown/HeadsUpAppearanceTest.kt)：提示卡片的显示、上滑与回弹。

0.6.17 发布前，在连接的一加设备上验证了底栏手势与单层染色；在 X9 Pro 的真实下载列表上检查了拖动中间状态、滚动背景和加深后的主题色。其他项目仍应验证自身的屏幕密度、亮暗主题、RTL、大字体和系统动画设置，不能直接把这里的设备结果视为全机型保证。

## 移植顺序

1. 先建立正确的页面采样源与浮层关系，让一个静止玻璃容器工作。
2. 加入单次底栏模糊和互斥底色，验证两个端点的颜色。
3. 连接 Pager 连续进度，验证中间位置。
4. 接入拖动、松手吸附和可中断的点击切换。
5. 加入整体按压缩放，保留标签语义与清晰的前景内容。
6. 用真实列表和像素测试检查，最后再按目标产品调整尺寸、颜色与模糊强度。

本文提取示例用于说明组件组织方式；完整集成与验证过的应用代码以本仓库 0.6.17 源码为准。
