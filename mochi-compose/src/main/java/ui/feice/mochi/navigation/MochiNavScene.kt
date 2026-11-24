// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotionTokens
import ui.feice.mochi.anim.awaitFrameSettle
import ui.feice.mochi.anim.rememberFramePeriodNanos
import ui.feice.mochi.effect.glass.GlassMotionWhile
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.MochiBackdrop
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberGlassFill
import ui.feice.mochi.effect.glass.source
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.utils.lerp

/**
 * [MochiNavHost] 的场景策略：不管逻辑栈怎么变，都返回同一个 key 的 [MochiNavScene]。
 * key 恒定（AnimatedSceneKey 按「场景类 + key」判等）⇒ NavDisplay 永远把它当同一段内容原地更新，
 * 不会跑场景间的转场；页面转场全部由引擎自己按逻辑栈的变化编排。
 */
internal class MochiNavSceneStrategy(
    private val engine: MochiNavEngine,
    private val geo: NavGeometry,
    private val config: MochiNavSceneConfig,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey> =
        MochiNavScene(engine, geo, config, entries)
}

/** 场景内容要从宿主读的设置：宿主每次重组写进来（快照状态，变了场景内容会跟着重组） */
internal class MochiNavSceneConfig {
    var spec by mutableStateOf<MochiNavSpec?>(null)
    var surface by mutableStateOf<MochiNavSurface?>(null)
    var predictiveBack by mutableStateOf(true)
    var windowBackdrop by mutableStateOf<MochiBackdrop?>(null)
}

/**
 * 承载整个可视栈的场景：内容就是旧的页面容器（页面排版、转场几何、来源变形、共享顶栏、玻璃录制都在这里）。
 * key 恒定，[previousEntries] 留空——预测性返回由引擎自己的手势处理接管，不交给 NavDisplay 的场景预览。
 * [entries] 只是 Nav3 场景簿记要的声明（单场景时排除表恒为空）；渲染和编排一律走引擎的实时状态（entryLog/entries），
 * 不信任场景对象里捕获的列表——AnimatedContent 在 key 不变时会用旧场景实例再组合一次内容。
 */
internal class MochiNavScene(
    private val engine: MochiNavEngine,
    private val geo: NavGeometry,
    private val config: MochiNavSceneConfig,
    sceneEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    override val key: Any = MochiSceneKey
    override val entries: List<NavEntry<NavKey>> = sceneEntries
    override val previousEntries: List<NavEntry<NavKey>> = emptyList()
    override val content: @Composable () -> Unit = { MochiNavSceneContent(engine, geo, config) }
}

private object MochiSceneKey

@Composable
private fun MochiNavSceneContent(engine: MochiNavEngine, geo: NavGeometry, config: MochiNavSceneConfig) {
    // 逻辑栈 → 可视条目的编排（push 掉头、pop 排队、popTo 群退、恢复的栈补齐），见 MochiNavSync.kt
    engine.sync()
    val surface = config.surface
    val predictiveBack = config.predictiveBack
    val scope = rememberCoroutineScope()
    val framePeriod = rememberFramePeriodNanos()
    // 侧推转场露出来的底色（MochiNavSpec.pushBackdrop 没指定时是分组样式的页面底色）
    val pushBackdrop = (config.spec ?: geo.spec).pushBackdrop.takeOrElse { MochiDefaults.groupedBackground() }

    BackHandler(enabled = !predictiveBack && engine.canPop) { engine.pop() }
    PredictiveBackHandler(enabled = predictiveBack && engine.canPop) { events ->
        val e = engine.entries.lastOrNull { !it.closing } ?: return@PredictiveBackHandler
        // 上一页还在「从来源打开」式地关闭（或者这一页就是那种页）：这次返回排队，手势不改这一页的画面，免得接手时跳一下
        val leaving = engine.entries.lastOrNull()?.takeIf { it.closing }
        val queued = leaving != null && (leaving.source != null || e.source != null)
        var startY = Float.NaN
        var lastP = 0f
        var lastT = 0L
        var vel = 0f
        e.backActive = true
        try {
            events.collect { ev ->
                if (startY.isNaN()) startY = ev.touchY
                e.backEdge = ev.swipeEdge
                e.backDy = ev.touchY - startY
                // 手势速度（拖动进度 / 秒），平滑一下；侧推页松手后按它接着滑出去
                val t = System.nanoTime()
                if (lastT != 0L) {
                    val dt = (t - lastT) / 1e9f
                    if (dt > 0f) vel = lerp(vel, (ev.progress - lastP) / dt, 0.5f)
                }
                lastP = ev.progress
                lastT = t
                if (!queued) e.back.snapTo(ev.progress)
            }
            // 上面还有一页在侧推退出也照样提交：这一页跟着一起滑走（不再要求它必须是栈顶，连续返回不会被吞掉）
            if (e in engine.entries && !e.closing) {
                if (e.source == null && !queued) {
                    // 侧推页：把此刻的画面换算回打开进度，关闭动画从这里、以手势速度连续地接着走
                    val pv = slideProgress(e)
                    e.progress.snapTo(pv)
                    e.back.snapTo(0f)
                    e.releaseVelocity = -SlideBackFollow * vel.coerceAtLeast(0f)
                }
                engine.pop()
            }
        } catch (c: CancellationException) {
            scope.launch { e.back.animateTo(0f, geo.spec.cancel) }
            throw c
        } finally {
            e.backActive = false
        }
    }

    val windowBackdrop = config.windowBackdrop
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (surface == null) {
                    Modifier
                } else {
                    Modifier.layout { measurable, constraints ->
                        // The sheet's stable content box excludes system insets and may extend
                        // below a floating sheet. Only visual layers use the actual shell height.
                        val h = surface.height()
                        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                    }
                },
            )
            .onSizeChanged { geo.size = it.toSize() }
            // 只在转场时画底色，平时不多画一层
            .drawBehind {
                if (!engine.isTransitioning) return@drawBehind
                // 有「从来源打开」的页在动就是黑底（像桌面打开应用）；只有侧推时用页面底色，露出来也不会闪黑
                val opening = engine.entries.any { it.source != null && it.inTransition }
                drawRect(if (opening) geo.spec.backdrop else pushBackdrop)
            },
    ) {
        // 页面录成整页的背景来源（浮层、转场里的共享顶栏玻璃取样它）；共享顶栏在录制外面，不会取样到自己
        Box(Modifier.fillMaxSize().then(if (windowBackdrop != null) Modifier.mochiBackdropSource(windowBackdrop) else Modifier)) {
            val entries = engine.entries
            val n = entries.size
            // 组合哪些页：从栈顶往下，只要这一页在转场（没把下面完全盖住）就把下面那页也带上，
            // 快速连续推入 / 返回时，好几页同时在动，每一页下面都有东西，不会露出黑底；
            // 至少保留栈顶下面那一页（滚动位置、来源元素的位置都还在，返回手势一开始就能露出来）
            var first = (n - 2).coerceAtLeast(0)
            while (first > 0 && entries[first + 1].inTransition) first--
            for (i in 0 until n) {
                val e = entries[i]
                key(e) {
                    // 侧推的前景页不带投影：下层页只压暗（见 NavGeometry.drawOver）
                    if (i >= first) {
                        EntryLayer(e, geo, surface) {
                            CompositionLocalProvider(LocalMochiNavEntry provides e) {
                                e.navEntry?.Content()
                            }
                        }
                    }
                    // Keep transitions alive even when a newer page fully covers an exiting entry.
                    LaunchedEffect(e, e.closing) {
                        val slide = e.source == null
                        // 换方向时上一个协程被取消，Animatable 取消时会把速度清零（endAnimation）：
                        // 用 closing 变化那一刻记下的速度接着走，掉头时速度连续（见 MochiNavEntry.closing）
                        val turnV = e.turnVelocity
                        val turnMoveV = e.turnMoveVelocity
                        e.turnVelocity = 0f
                        e.turnMoveVelocity = 0f
                        if (!e.closing) {
                            // 新打开的页：先等它组合、排版、绘制完、帧率回稳（第一次打开时类加载、首屏子组合可能要几百毫秒）
                            // 再开始动，不然动画的开头几帧被这几帧吞掉，看起来是从卡片一下跳到半屏。等待期间画面还停在起点
                            if (e.progress.value == 0f && !e.started) awaitFrameSettle(framePeriod)
                            if (e.closing) return@LaunchedEffect
                            e.started = true
                            // 打开；关闭途中又被 push 回来时也走这里，从当前位置和速度掉头
                            if (slide) {
                                if (e.progress.value != 1f) e.progress.animateTo(1f, geo.spec.push, initialVelocity = turnV)
                            } else {
                                coroutineScope {
                                    // 关闭途中掉头：透明度从此刻的值按淡化时钟过渡回「跟进度」，不跳
                                    if (e.fadeClosing) {
                                        geo.turnFade(e, closing = false)
                                        e.fade.snapTo(0f)
                                        launch { e.fade.animateTo(1f, MochiMotionTokens.Fade) }
                                    }
                                    // 位置弹簧会过冲：取消后停在 1 以上时也要落回 1（取消后 isRunning 已是 false）
                                    launch {
                                        if (e.move.value != 1f) e.move.animateTo(1f, geo.spec.openMove, initialVelocity = turnMoveV)
                                    }
                                    if (e.progress.value != 1f) e.progress.animateTo(1f, geo.spec.open, initialVelocity = turnV)
                                }
                            }
                            return@LaunchedEffect
                        }
                        if (slide) {
                            // 侧推：同一条弹簧直接往 0 走，打开途中返回也是从当前位置连续掉头
                            val v = e.releaseVelocity
                            e.releaseVelocity = 0f
                            e.progress.animateTo(0f, geo.spec.push, initialVelocity = if (v != 0f) v else turnV)
                            if (!e.closing) return@LaunchedEffect
                            engine.onClosed(e)
                            return@LaunchedEffect
                        }
                        // 有来源：同一条弹簧从当前位置往 0 走（不重新起算，所以能随时掉头）；
                        // 返回手势的缩小量一起弹回 0，落地时正好是来源的矩形。
                        // 透明度走自己的短时长（淡化时钟），在容器还明显缩小的前 150ms 里交接完
                        geo.turnFade(e, closing = true)
                        e.fade.snapTo(0f)
                        coroutineScope {
                            launch { e.fade.animateTo(1f, MochiMotionTokens.Fade) }
                            launch { e.back.animateTo(0f, geo.spec.close) }
                            launch { e.move.animateTo(0f, geo.spec.closeMove, initialVelocity = turnMoveV) }
                            e.progress.animateTo(0f, geo.spec.close, initialVelocity = turnV)
                        }
                        if (!e.closing) return@LaunchedEffect
                        engine.onClosed(e)
                    }
                }
            }
        }
        // 跨页共享的顶栏：侧推转场中画在所有页面上面、留在顶栏原位变形（见 MochiNavBarItem.kt）。不拦触摸
        // 玻璃按钮的形状画成玻璃（取样整页录制），其余形状和所有图标画在 Spacer 里
        // 不录整页时（windowGlass = false）玻璃按钮取样各自页面的背景，见 SharedBarGlass
        val barGlass = rememberGlassFill(GlassRole.Control, windowBackdrop?.source)
        // 转场期间玻璃走运动档（只降真在重画的那几块）、暂停亮度回读，见 GlassMotion
        GlassMotionWhile(engine.isTransitioning)
        if (engine.isTransitioning) SharedBarGlass(engine, barGlass)
        val barPaths = remember { BarPathCache() }
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                // 从来源打开时，下层页的顶栏 / 底栏（mochiNavOccluder）先盖在变形中的容器上、再随进度淡掉，不会被一下盖住
                // 共享元素飞在所有页面上面、下层页的栏副本下面
                geo.drawShared(this)
                geo.drawOccluders(this)
                drawSharedBarItems(engine, barPaths, glass = true)
            },
        )
    }
}

@Composable
private fun EntryLayer(e: MochiNavEntry, geo: NavGeometry, surface: MochiNavSurface?, content: @Composable () -> Unit) {
    val cover = if (e.source != null) rememberGraphicsLayer() else null
    val sourceClip = remember { NavPageClip() }
    val slideClip = remember { NavPageClip() }
    DisposableEffect(e, cover) {
        onDispose {
            e.coverLayer = null
            e.coverCoords = null
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { e.coverCoords = it }
            // 来源容器在主机坐标裁切。只有下层页有玻璃取样方、且遮挡栏还可见时才录制。
            .drawWithContent {
                val outline = geo.sourceOutline(e)
                if (cover != null && outline != null && geo.needsCover(e)) {
                    val path = sourceClip.pathFor(outline)
                    cover.record {
                        clipPath(path) { this@drawWithContent.drawContent() }
                    }
                    e.coverLayer = cover
                    drawLayer(cover)
                } else {
                    e.coverLayer = null
                    if (outline != null || geo.clipsSlide(e)) {
                        clipRect { sourceClip.draw(this@drawWithContent, outline) }
                    } else {
                        drawContent()
                    }
                }
            }
            .graphicsLayer { geo.applyLayer(e, this) }
            .drawWithContent {
                slideClip.draw(this, geo.slideOutline(e))
            }
            .drawWithContent {
                // 完全被盖住的下层页不画。不用 alpha = 0：那样露出来时只改了图层属性，
                // 内容可能没重新录制（真机上会画成空白）；在这里跳过，露出来时这块会重新执行、整页重画。
                // 这里只读「是否转场中」（derivedStateOf），不读每帧变化的进度，转场中不会每帧重录整页
                if (!geo.isHidden(e)) {
                    // The incoming page also covers the previous page's dimming below its safe body.
                    // 玻璃面板：页面是透明的，底色按面板此刻的不透明度画（半屏透出面板的玻璃，全屏和面板一样不透明）
                    if (surface != null) drawRect(surface.color, alpha = if (surface.glass == null) 1f else surface.opaque())
                    drawContent()
                }
            },
    ) {
        val scope = rememberCoroutineScope()
        // 正在缩回来源：只有看得见的那块接触摸（画面不裁）。点来源卡片照常掉头长回来，这一块以外落到下层页上
        val hit = if (e.closing) Modifier.visibleHitArea(e, geo) else Modifier
        Box(
            hit
                .fillMaxSize()
                // 页面内容单独一层：被盖住时外层跳过绘制、露出来时外层只重录一条「画这一层」，
                // 页面本身的绘制记录保留着，转场第一帧不用把整页重新录一遍。
                // 转场中这一层画进离屏纹理：外层每帧缩放、裁切的只是这张图，文字字形和路径不随缩放每帧重新光栅化；
                // 每帧变的盖色 / 压暗在旁边自己那一层，不会让这张图作废
                .graphicsLayer {
                    // 被「从来源打开」的页盖着的下层页也一样：它这段时间只缩放（页里收起的栏由下面那层代画）
                    // 侧推也缓存（动的页、露出来的下层页都只平移）：页里的玻璃 / 模糊不用每帧重跑着色器，开液态玻璃时转场不卡
                    val nav = geo.nav
                    val cached = e.inTransition || nav.above(e)?.inTransition == true || nav.isCarriedUnderSource(e)
                    compositingStrategy = if (cached) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .then(
                    if (surface == null) {
                        Modifier
                    } else {
                        Modifier.layout { measurable, constraints ->
                            // Keep text and cards at the original safe height while their page's
                            // background, scrim, shadow and corner clipping reach the visible bottom.
                            val h = surface.contentHeight()
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                        }
                    },
                )
                // iOS 26：在页面任意位置往右滑都能返回（不必从屏幕边缘开始）
                .pointerInput(e) { fullSwipeBack(e, geo, scope) }
                // 整页都算命中，触摸不会漏到下面那页
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .onPlaced { e.contentCoords = it },
        ) { content() }
        // 从来源打开的转场中，这一页收起的栏（标签栏、底部工具栏）在这里代画：位置、下滑和原来一样，
        // 压在下面的压暗层下面；它每帧在动，放在自己的图层里，不会让上面那张整页缓存作废
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {}
                .drawBehind { geo.drawCarriedBars(e, this) },
        )
        // 正在退出的页不接触摸（iOS 也是）：点在它还露着的部分不会误触，点在露出来的下层页上照常。
        // 有来源的页正在缩回去时点它 = 再打开：从当前位置掉头长回来
        if (e.closing) {
            val engine = geo.nav
            Spacer(
                hit
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                for (ch in ev.changes) ch.consume()
                                if (e.source != null && e.closing && ev.changes.any { it.changedToDown() }) engine.push(e.key)
                            }
                        }
                    },
            )
        }
        // 盖色 / 压暗每帧都变：放在自己的小图层里，只重录这一层，页面内容不跟着重录
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {}
                .drawBehind { geo.drawOver(e, this) },
        )
    }
}

/** 只把顶上 [NavGeometry.visibleHeight] 那么高报成自己的大小（命中范围跟着缩），内容照整页排版、照常画 */
private fun Modifier.visibleHitArea(e: MochiNavEntry, geo: NavGeometry): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = ceil(geo.visibleHeight(e)).toInt().coerceIn(constraints.minHeight, p.height)
    layout(p.width, h) { p.place(0, 0) }
}

/** 侧推时下层页往左退多少（屏宽的比例，iOS 约 0.3） */
internal const val SlideUnderShift = 0.3f

/** 预测性返回拖到底时，侧推页最多滑出去多少（屏宽比例） */
internal const val SlideBackFollow = 0.9f

/** 侧推页此刻的「可见打开程度」：打开进度和返回手势里滑得更远的那个 */
internal fun slideProgress(e: MochiNavEntry): Float = 1f - max(1f - e.progress.value, SlideBackFollow * e.back.value)
