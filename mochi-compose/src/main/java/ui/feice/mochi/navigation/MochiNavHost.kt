// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import ui.feice.mochi.anim.MochiMotionTokens
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.anim.MochiSprings
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.effect.glass.source
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/**
 * 页面转场的手感。
 * @param open 打开（来源 → 整屏）的弹簧，接近 iOS 从桌面打开应用
 * @param close 关闭（整屏 / 松手时的画面 → 来源）的弹簧
 * @param cancel 预测性返回取消后弹回整屏的弹簧
 * @param push 侧推（没有来源的 push / pop）的弹簧：iOS 是临界阻尼，不回弹。打开和关闭用同一条，中途掉头时速度连续
 * @param underScale 来源转场的背景比例，默认 1 保持原位；显式小于 1 可启用旧的景深缩放
 * @param backScale 预测性返回拖到底时页面缩到多少
 * @param scrim 侧推时下层页面被压住盖上的暗色（alpha 是最深时的）
 * @param sourceScrim 一镜到底（从来源打开）时下层页面盖上的暗色，比侧推深
 * @param backdrop 从来源打开的转场最底下的底色（下层页缩小后露出的四周和圆角外侧），像从桌面打开应用一样是黑的
 * @param pushBackdrop 侧推转场最底下的底色：任何一帧露出来的地方都用页面底色填上，不会闪黑。
 *   [Color.Unspecified] = iOS 分组样式的页面底色（MochiDefaults.groupedBackground）
 */
@Immutable
public data class MochiNavSpec(
    val open: AnimationSpec<Float>,
    val close: AnimationSpec<Float>,
    val cancel: AnimationSpec<Float>,
    val underScale: Float = 1f,
    val backScale: Float = 0.86f,
    val scrim: Color = Color.Black.copy(alpha = 0.18f),
    val backdrop: Color = Color.Black,
    val push: AnimationSpec<Float> = MochiMotionTokens.Page,
    val pushBackdrop: Color = Color.Unspecified,
    val openMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
    val closeMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
    val sourceScrim: Color = Color.Black.copy(alpha = 0.36f),
)

public object MochiNavDefaults {
    @Composable
    public fun spec(
        // iOS 的 .smooth(duration: 0.3)：不回弹、300ms 平滑停住。回弹的弹簧尾巴拖得长，
        // 转场一直算「没停稳」，手势和点击都要等它，用起来粘手。途中换方向照样保留速度
        open: AnimationSpec<Float> = MochiSprings.smooth(300, visibilityThreshold = 0.0005f),
        close: AnimationSpec<Float> = MochiSprings.smooth(300, visibilityThreshold = 0.0005f),
        cancel: AnimationSpec<Float> = MochiMotions.responseSpring(0.35f, 1f),
        underScale: Float = 1f,
        backScale: Float = 0.86f,
        scrim: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.4f else 0.18f),
        backdrop: Color = Color.Black,
        push: AnimationSpec<Float> = MochiMotionTokens.Page,
        pushBackdrop: Color = MochiDefaults.groupedBackground(),
        // 位移和放大分开：位置用 iOS 的 snappy 300ms（回弹 0.15），到位时轻轻冲过一点再落回；大小、圆角仍用上面的 smooth
        openMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
        closeMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
        // 一镜到底的背景压暗：浅色 36%、深色 60%，约是侧推的两倍
        sourceScrim: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.6f else 0.36f),
    ): MochiNavSpec = remember(
        open, close, cancel, underScale, backScale, scrim, backdrop, push, pushBackdrop, openMove, closeMove, sourceScrim,
    ) {
        MochiNavSpec(open, close, cancel, underScale, backScale, scrim, backdrop, push, pushBackdrop, openMove, closeMove, sourceScrim)
    }
}

/**
 * 页面容器：渲染 [backStack]（Navigation 3 的返回栈），页面转场、来源变形、预测性返回都由库接管，
 * 视觉是 iOS 26 的容器转场。条目（[NavEntry]）由 [entryProvider] 提供——通常用 Navigation 3 的
 * `entryProvider { entry<Home> { … } }` DSL 写。
 *
 * - 有来源（key 实现 [MochiNavKey] 并带 `mochiSource`）时是「容器变形」：卡片本身变成页面，关闭时缩回去，位置、
 *   大小只由弹簧进度决定，下层页面默认保持原位，只随进度压暗。
 * - 没有来源时是 iOS 的侧推：新页从右边推进来，下层页往左退 30% 并压暗，新页左边缘带一道投影。
 *   应用侧推页的操作使用带稳定 navId 的库顶栏控件，图标配置稳定 contentKey；布局容器本身不自动登记操作。
 * - 预测性返回：有来源的页跟手缩小，侧推的页跟着手指往右滑，松手按手势末速度接着滑出去，取消就弹回。
 *   需要在 AndroidManifest 的 application 上加 `android:enableOnBackInvokedCallback="true"`。
 * - 转场中页面（包括被盖住的下层页）默认使用屏幕的物理圆角（[rememberMochiScreenCorners]），
 *   面板内则继承面板轮廓，全屏补底时底部两角保持直角。
 *
 * 页面始终按整屏排版，转场只改 graphicsLayer，不会每帧重新布局。栈顶下面那一页保持组合
 * （滚动位置、来源元素的位置都还在），完全被盖住时不绘制；更深的页面状态由条目装饰器保存。
 * 条目装饰器默认带 SaveableStateHolder（[rememberSaveableStateHolderNavEntryDecorator]），
 * Navigation 3 还会自动加上场景条目登记和「离开逻辑栈的条目生命周期封顶在 CREATED」（见
 * androidx.navigation3.scene），要加自己的装饰器（结果回传、ViewModel 域等）就放进 [entryDecorators]。
 *
 * @param backStack 应用自己创建的返回栈：要跨进程重建就存 key 的序列化、用 Navigation 3 的
 *   `rememberNavBackStack(Home)`；只是普通用法 `remember { NavBackStack(Home) }` 就够。
 *   栈不能为空（Navigation 3 的约定）；返回就是 `backStack.removeLast()`，多点返回用 [mochiPopTo]
 * @param predictiveBack 系统返回手势预览；false 时仍可正常返回，内层主机和面板继承此设置。应用内全屏右滑手势不受影响
 * @param corners 页面转场圆角覆盖值；null 在面板内继承面板圆角，其他位置使用物理屏幕圆角；[MochiScreenCorners.None] 显式关闭内页转场圆角
 * @param windowGlass 最外层主机把整页录下来给浮层（菜单、警告框、面板）的玻璃取样。页面里有不能画进离屏图层的原生视图时传 false：
 *   WebView 在 Vulkan 渲染的机器上被玻璃取样会让渲染线程崩溃。关掉后浮层画成材质的纯色，页面里的栏玻璃不受影响
 */
@Composable
public fun MochiNavHost(
    backStack: NavBackStack<NavKey>,
    modifier: Modifier = Modifier,
    spec: MochiNavSpec = MochiNavDefaults.spec(),
    corners: MochiScreenCorners? = null,
    predictiveBack: Boolean = LocalMochiPredictiveBack.current,
    windowGlass: Boolean = true,
    entryDecorators: List<NavEntryDecorator<NavKey>> = listOf(rememberSaveableStateHolderNavEntryDecorator()),
    entryProvider: (key: NavKey) -> NavEntry<NavKey>,
) {
    val engine = remember(backStack) { MochiNavEngine(backStack) }
    val geo = remember(engine) { NavGeometry(engine) }
    geo.spec = spec
    val screenCorners = rememberMochiScreenCorners()
    val surface = LocalMochiNavSurface.current
    geo.defaultCorners = corners ?: screenCorners
    geo.enclosingCorners = if (corners == null) surface?.corners else null
    geo.glassOpaque = surface?.takeIf { it.glass != null }?.opaque
    geo.fallbackCorner = if (corners == null && surface == null) with(LocalDensity.current) { SlideFallbackCorner.toPx() } else 0f

    // 包一层 entryProvider：每个 key 最新生成的条目登记进引擎（sync 按 key 配对，见 MochiNavSync.kt）
    val latestProvider by rememberUpdatedState(entryProvider)
    val loggedProvider = remember(engine) {
        { key: NavKey ->
            val entry = latestProvider(key)
            engine.entryLog[key] = entry
            entry
        }
    }

    // 面板里的内层主机：把自己的引擎放进面板登记的槽位，面板的返回处理读它（子页先退、根页关面板）
    val slot = LocalMochiNavEngineSlot.current
    if (slot != null && slot.stack === backStack) {
        DisposableEffect(engine) {
            slot.engine = engine
            onDispose { if (slot.engine === engine) slot.engine = null }
        }
    }

    val config = remember(engine) { MochiNavSceneConfig() }
    config.spec = spec
    config.surface = surface
    config.predictiveBack = predictiveBack
    // 最外层的导航主机把整页录下来给浮层（菜单、警告框、面板）的玻璃取样；面板里的内层主机、没有玻璃的主题不录
    config.windowBackdrop =
        if (windowGlass && surface == null && LocalGlassSources.current.window == null && MochiTheme.materials.anyGlass) {
            rememberMochiBackdrop(MochiDefaults.groupedBackground())
        } else {
            null
        }
    CompositionLocalProvider(
        LocalMochiNavEngine provides engine,
        LocalMochiNavBackStack provides backStack,
        LocalMochiNavSurface provides null,
        LocalMochiPredictiveBack provides predictiveBack,
        LocalGlassSources provides LocalGlassSources.current.let { it.copy(window = config.windowBackdrop?.source ?: it.window) },
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { engine.pop() },
            entryDecorators = entryDecorators,
            sceneStrategies = listOf(remember(engine, geo, config) { MochiNavSceneStrategy(engine, geo, config) }),
            entryProvider = loggedProvider,
        )
    }
}

/** 屏幕没有物理圆角时侧推用的圆角 */
internal val SlideFallbackCorner = 28.dp
