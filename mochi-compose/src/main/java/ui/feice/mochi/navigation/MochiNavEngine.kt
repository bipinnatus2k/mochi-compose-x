// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // LocalMochiNavEngine/LocalMochiNavEntry/LocalMochiNavEngineSlot：库内部通道

package ui.feice.mochi.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import ui.feice.mochi.utils.smoothstep

/**
 * 转场引擎：包着应用的 [NavBackStack]（Navigation 3 的逻辑栈），管视觉上滞后于它的页面栈、
 * 来源/遮挡物/收起栏/共享顶栏的登记表，和转场几何。逻辑栈由应用（或 [MochiNavHost] 的返回处理）直接改，
 * 引擎在 [MochiNavScene] 的场景内容里对比逻辑栈和可视条目，按 push / pop 的形状编排转场（见 MochiNavSync.kt）。
 */
@Stable
internal class MochiNavEngine(internal val stack: NavBackStack<NavKey>) {

    /** 可视条目：逻辑栈上的页 + 正在关闭（pop 了但还在演退场动画）的页 + 排队等关闭的页 */
    internal val entries = mutableStateListOf<MochiNavEntry>()

    /** 最新一轮 entryProvider 生成的条目（宿主包一层登记），sync 用它把栈上的 key 配到 Nav3 条目 */
    internal val entryLog = mutableStateMapOf<NavKey, NavEntry<NavKey>>()

    /** 上一次 sync 看到的逻辑栈，用来 diff 出 push / pop（见 MochiNavSync.kt） */
    internal var lastKeys: List<NavKey> = stack.toList()

    /** 排队中的返回（来源页还在变形时 pop 又来了：等它关完再关这一页） */
    internal val pending = ArrayDeque<MochiNavEntry>()

    /** 能不能返回（可视栈里除了根还有页） */
    internal val canPop: Boolean get() = entries.count { !it.closing } > 1

    /** 有页面正在打开、拖动返回或关闭 */
    internal val isTransitioning: Boolean get() = entries.any { it.inTransition }

    /** 打开一页：key 直接进逻辑栈，转场由 sync 编排 */
    internal fun push(key: NavKey) {
        stack.add(key)
    }

    /** 返回上一页（带转场）。逻辑栈里只剩根时返回 false */
    internal fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 一页的关闭动画走完：把它从可视栈摘掉，有排队的返回就接着关下一页 */
    internal fun onClosed(entry: MochiNavEntry) {
        entries.remove(entry)
        if (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next in entries && !next.closing) next.closing = true
        }
    }

    /**
     * [entry] 被上面那页盖住了多少：0 = 上面没页或没打开，1 = 完全盖住。跟着转场进度和返回手势走
     */
    internal fun coverOf(entry: MochiNavEntry): Float {
        val above = above(entry) ?: return 0f
        return if (above.source == null) {
            slideCoverOf(entry)
        } else {
            // 拖动返回时一边拖一边露出来（松手就回到这一页）
            (above.progress.value.coerceIn(0f, 1f) * (1f - CoverBackReveal * above.back.value)).coerceIn(0f, 1f)
        }
    }

    /** Keep the exposed page's offset continuous when an outgoing slide is removed. */
    internal fun slideCoverOf(entry: MochiNavEntry): Float {
        val index = entries.indexOf(entry)
        if (index < 0) return 0f
        var cover = 0f
        for (i in index + 1 until entries.size) {
            val page = entries[i]
            if (page.source != null) break
            cover = maxOf(cover, slideProgress(page))
            if (cover >= 1f) return 1f
        }
        return cover
    }

    /** 栈里这一页上面那一页 */
    internal fun above(entry: MochiNavEntry): MochiNavEntry? {
        val i = entries.indexOf(entry)
        return if (i >= 0 && i < entries.size - 1) entries[i + 1] else null
    }

    internal fun under(entry: MochiNavEntry): MochiNavEntry? {
        val i = entries.indexOf(entry)
        return if (i > 0) entries[i - 1] else null
    }

    // —— 来源登记（布局 / 绘制时读，不需要触发重组）——

    private val sources = HashMap<String, SourceHandle>()

    internal fun registerSource(h: SourceHandle) {
        sources[h.key] = h
    }

    internal fun unregisterSource(h: SourceHandle) {
        if (sources[h.key] === h) sources.remove(h.key)
    }

    internal fun source(key: String): SourceHandle? = sources[key]?.takeIf { it.shown() }

    /**
     * 这个来源对应的页面还在栈里（打开着或正在转场），来源自己要藏起来：
     * 转场里由页面容器接手画它录下来的画面（跟着容器一起缩放、淡出），关闭落地时正好和它原样重合
     */
    internal fun isSourceActive(key: String): Boolean = entries.any { it.source == key }

    // —— 共享元素（mochiNavSharedElement）：从来源打开的转场里，来源页和目标页同 key 的元素由宿主画成一个飞过去的元素 ——

    private val shared = ArrayList<SharedHandle>()

    internal fun registerShared(h: SharedHandle) {
        shared += h
    }

    internal fun unregisterShared(h: SharedHandle) {
        shared -= h
    }

    /** [entry] 这一页里登记的共享元素 */
    internal fun sharedOf(entry: MochiNavEntry): List<SharedHandle> = shared.filter { it.entry === entry }

    /** [entry] 这一页里 key 为 [key] 的共享元素 */
    internal fun sharedFor(key: String, entry: MochiNavEntry): SharedHandle? = shared.firstOrNull { it.key == key && it.entry === entry }

    /**
     * 这个共享元素此刻在飞（原地的要藏起来）：栈顶页是从来源打开的、正在转场，元素在栈顶页或它下面那页，
     * 另一页也有同 key 的元素。读栈和 inTransition（都是可观察状态），转场开始 / 结束时读者会重画
     */
    internal fun isSharedActive(h: SharedHandle): Boolean {
        val top = entries.lastOrNull() ?: return false
        if (top.source == null || !top.inTransition) return false
        val under = under(top) ?: return false
        val other = when (h.entry) {
            top -> under
            under -> top
            else -> return false
        }
        return shared.any { it !== h && it.key == h.key && it.entry === other }
    }

    // —— 遮挡物（顶栏、底栏、滚动边缘渐隐，见 mochiNavOccluder）：从来源打开时先盖在变形的容器上再淡掉 ——

    private val occluders = ArrayList<OccluderHandle>()

    internal fun registerOccluder(h: OccluderHandle) {
        occluders += h
    }

    internal fun unregisterOccluder(h: OccluderHandle) {
        occluders -= h
    }

    internal fun occludersOf(entry: MochiNavEntry): List<OccluderHandle> = occluders.filter { it.entry === entry && it.shown() }

    // —— 收起的栏（mochiHideWhenCovered）：从来源打开时由场景内容代画，下层页内容才能整页离屏缓存 ——

    private val hidden = ArrayList<HideHandle>()

    internal fun registerHidden(h: HideHandle) {
        hidden += h
    }

    internal fun unregisterHidden(h: HideHandle) {
        hidden -= h
    }

    internal fun hiddenOf(entry: MochiNavEntry): List<HideHandle> = hidden.filter { it.entry === entry && it.shown() }

    /**
     * 这一页正被一个「从来源打开」的页盖着、在转场中：页里收起的栏不在页面里画、由场景内容代画（位移照常），
     * 页面内容这段时间不变，可以整页画进离屏纹理只缩放
     */
    internal fun isCarriedUnderSource(entry: MochiNavEntry): Boolean {
        val above = above(entry) ?: return false
        return above.source != null && above.inTransition
    }

    /**
     * 这一页在转场中，或被上面的页盖着：页里取背后亮度的效果（玻璃标签栏、滚动边缘）这时不回读屏幕。
     * 转场每帧都在变，读到的是半路的画面，回读还会让渲染线程等 GPU，正好卡在动画上
     */
    internal fun isMovingOrCovered(entry: MochiNavEntry): Boolean = entry.inTransition || above(entry) != null

    /** [c] 在这一页某个收起的栏里、而栏正由场景内容代画时，栏此刻往下滑了多少（px）；否则 0 */
    internal fun carriedShift(entry: MochiNavEntry, c: LayoutCoordinates): Float {
        if (!isCarriedUnderSource(entry)) return 0f
        for (h in hiddenOf(entry)) {
            val hc = h.coords ?: continue
            var p: LayoutCoordinates? = c
            while (p != null) {
                if (p === hc) return hideOffset(h)
                p = p.parentCoordinates
            }
        }
        return 0f
    }

    /** 收起的栏此刻往下滑了多少（px） */
    internal fun hideOffset(h: HideHandle): Float {
        val e = h.entry ?: return 0f
        // 侧推：照 iOS 的 hidesBottomBarWhenPushed，栏留在下层页里、跟着它往左退，被新页盖住；不往下滑
        if (above(e)?.source == null) return 0f
        return (h.height + h.below) * smoothstep(0f, HideDoneAt, coverOf(e))
    }

    // —— 跨页共享的顶栏（形状和内容，见 MochiNavBarItem.kt）——

    private val bars = ArrayList<BarHandle>()

    /** 登记表变了就加一，读它的绘制（按钮自己、场景内容的最上层）跟着重画 */
    private var barsVersion by mutableIntStateOf(0)

    internal fun registerBar(h: BarHandle) {
        bars += h
        barsVersion++
    }

    internal fun unregisterBar(h: BarHandle) {
        bars -= h
        barsVersion++
    }

    internal fun barsOf(entry: MochiNavEntry): List<BarHandle> {
        barsVersion
        return bars.filter { it.entry === entry && it.shown() }
    }

    /**
     * 顶栏正在变形的一串页：从栈顶往下，连续的侧推转场中的页，再加上它们下面那一页（起点）。
     * 快速连续推入 / 返回时好几页同时在动，顶栏从起点开始按每一页的进度依次变过去，只画一套、全程连续。
     * 栈顶没在侧推转场时为空
     */
    internal fun slideChain(): List<MochiNavEntry> {
        val n = entries.size
        if (n < 2) return emptyList()
        fun sliding(e: MochiNavEntry) = e.source == null && e.inTransition
        if (!sliding(entries[n - 1])) return emptyList()
        var i = n - 1
        while (i >= 1 && sliding(entries[i])) i--
        return entries.subList(i, n).toList()
    }

    /** 这个顶栏按钮所在的页在变形的那一串里：由场景内容留在顶栏原位画，它自己的页里不画 */
    internal fun isBarCarried(h: BarHandle): Boolean {
        val e = h.entry ?: return false
        val page = e.contentCoords ?: return false
        if (h.restRect(page) == null) return false
        return slideChain().any { it === e }
    }
}

/** 当前 [MochiNavHost] 的转场引擎 */
internal val LocalMochiNavEngine = staticCompositionLocalOf<MochiNavEngine?> { null }

/** 当前内容所在的页 */
internal val LocalMochiNavEntry = staticCompositionLocalOf<MochiNavEntry?> { null }

/**
 * 面板（MochiSheet）读内层 [MochiNavHost] 转场状态的通道：面板在宿主外面，拿不到它的 CompositionLocal，
 * 双方用同一个 backStack 对上（面板登记槽，宿主往里放引擎）。见 MochiSheet 的返回处理
 */
internal class MochiNavEngineSlot(val stack: NavBackStack<*>) {
    var engine by mutableStateOf<MochiNavEngine?>(null)
}

internal val LocalMochiNavEngineSlot = staticCompositionLocalOf<MochiNavEngineSlot?> { null }

/** 栈里的一页和它的转场状态（可视条目；逻辑栈在 Navigation 3 的 backStack 上） */
@Stable
internal class MochiNavEntry(
    val key: NavKey,
    /** Navigation 3 的内容身份（SaveableStateProvider、movableContent 都按它走） */
    val contentKey: Any,
    opened: Boolean,
) {
    /** 来源元素（卡片）的 key；null = 侧推 */
    val source: String? = (key as? MochiNavKey)?.mochiSource

    /** 这个 key 最新的 Nav3 条目：场景内容用它渲染页面；关闭中的页保留 pop 前那一条，画着退场 */
    var navEntry: NavEntry<NavKey>? = null

    /** 打开进度：0 = 缩在来源处，1 = 整屏（弹簧，可能略微过冲）。打开和关闭是同一条弹簧，随时掉头 */
    val progress = Animatable(if (opened) 1f else 0f)

    /**
     * 从来源打开的页：位置的进度（页面中心从来源移到屏幕中心）。和 [progress]（大小、圆角、淡入淡出）分开，
     * 用一根带一点回弹的弹簧，到位时轻轻冲过一点再落回；侧推页不用它
     */
    val move = Animatable(if (opened) 1f else 0f)

    /** 侧推页：预测性返回松手那一刻的速度（打开进度 / 秒，往关闭方向为负），关闭动画从这个速度接着走 */
    var releaseVelocity = 0f

    /** 预测性返回的拖动进度 0..1 */
    val back = Animatable(0f)

    /** 从哪条边滑进来（BackEventCompat.EDGE_*） */
    var backEdge by mutableIntStateOf(0)

    /** 手指竖直方向相对按下点的位移（px） */
    var backDy by mutableFloatStateOf(0f)

    /** 返回手势进行中（从第一个事件起，进度还是 0 时下层页就要露出来） */
    var backActive by mutableStateOf(false)

    /**
     * 已提交返回，正在缩回去。
     * 换方向那一刻把 [progress] / [move] 的速度记下来：换方向靠重启 LaunchedEffect，
     * 旧协程被取消时 Animatable 会清零速度（endAnimation），新动画读不到当前速度，要用这里记下的接着走
     */
    var closing: Boolean
        get() = closingState
        set(value) {
            if (value != closingState) {
                turnVelocity = progress.velocity
                turnMoveVelocity = move.velocity
            }
            closingState = value
        }
    private var closingState by mutableStateOf(false)

    /** [closing] 变化那一刻 [progress] 的速度（进度 / 秒），掉头的动画从这里接着走，用完清零 */
    var turnVelocity = 0f

    /** [closing] 变化那一刻 [move] 的速度 */
    var turnMoveVelocity = 0f

    /**
     * 从来源打开的页：交叉淡化的时钟（0 → 1，按时间走，[ui.feice.mochi.anim.MochiMotionTokens.Fade]）。
     * 透明度不跟弹簧进度：关闭方向的弹簧后半段很慢，跟进度的话淡化会拖到几何几乎不动的尾巴里。
     * 刚打开时停在 1，透明度照旧只看进度；关闭、关闭途中掉头时从 0 走到 1，见 NavGeometry.fadeOf
     */
    val fade = Animatable(1f)

    /** 淡化处在关闭方向（页面被来源底色盖住、卡片画面淡回来）；false = 打开方向（跟进度） */
    var fadeClosing = false

    /** 换方向那一刻画着的卡片画面 / 来源底色 / 下层栏副本的透明度，新方向从这里接，不跳 */
    var fadeFromCard = 0f
    var fadeFromCover = 0f
    var fadeFromOccluder = 0f

    /** 打开动画已经开始过（第一次打开前要先等页面画完第一帧） */
    var started = false

    /** 关闭开始那一刻的画面已经记下（之前那一帧仍按打开时的画面画，不跳） */
    var closeCaptured by mutableStateOf(false)

    /** 关闭开始那一刻的画面（关闭动画从这里连续地缩回来源） */
    var closeFrom: Rect? = null
    var closeUnder: Float = 1f
    var closeMix: Float = 1f
    var closeCover: Float = 0f

    /** 这一页内容根节点的坐标（未变换的页面坐标系） */
    var contentCoords: LayoutCoordinates? = null

    /** 这一页变换后的整页画面（屏幕坐标系）和它的坐标：下层页的玻璃在转场中透过它看到这一页（见 rememberMochiNavCoverBackdrop） */
    var coverReaderCount by mutableIntStateOf(0)
    var coverLayer: GraphicsLayer? = null
    var coverCoords: LayoutCoordinates? = null

    /**
     * 打开、拖动返回、关闭中。用 derivedStateOf：只在「是否转场中」变化时通知读者，
     * 不会因为进度每帧变化让读它的组合 / 绘制每帧都重来
     */
    val inTransition: Boolean by derivedStateOf {
        closing || backActive || progress.isRunning || progress.value != 1f || back.value != 0f ||
            (source != null && (move.isRunning || move.value != 1f))
    }
}

/** 来源元素（卡片）的登记信息 */
internal class SourceHandle(var key: String) {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null

    /** 来源元素录下来的画面（转场时画进变形中的容器）和它的大小 */
    var layer: GraphicsLayer? = null
    var size: Size = Size.Zero
    var radius: Float = 0f
    var color: Color = Color.Transparent
}

/** 遮挡物的登记信息：位置 + 录下来的画面 */
internal class HideHandle {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
    var height: Float = 0f
    var below: Float = 0f
}

internal class OccluderHandle {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
}

/** 有来源的页拖动返回拖到底时，下面那页「露出来」的比例 */
private const val CoverBackReveal = 0.6f
