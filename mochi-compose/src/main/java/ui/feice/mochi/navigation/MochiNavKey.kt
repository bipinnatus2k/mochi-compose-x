// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // LocalMochiNavBackStack：页面里取栈返回，公开 API

package ui.feice.mochi.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * 想用「从来源打开」（容器变形）转场的页面 key。给 key 带上来源元素的 key
 * （[mochiNavSource] 登记的那个字符串），push 时页面从那个元素长成整屏、关闭时缩回去；
 * 来源是 key 上的数据，进程重建后照旧。不给（null，默认）就是普通侧推。
 *
 * ```kotlin
 * @Serializable data object Home : NavKey
 * @Serializable data class Item(val id: String, override val mochiSource: String? = null) : MochiNavKey
 *
 * val stack = rememberNavBackStack(Home)
 * MochiNavHost(stack, entryProvider = entryProvider { entry<Home> { … } entry<Item> { key -> … } })
 * stack.add(Item("42", mochiSource = "item-42"))   // 从卡片「打开应用」式展开
 * ```
 *
 * 普通 [NavKey] 也能用（没有来源、走侧推），但 key 的等值语义是 Navigation 3 的：
 * **等值的 key 就是同一个目的地**——push 已在栈里的 key 不会再开一页，想开第二页让 key 带上不同的参数。
 */
public interface MochiNavKey : NavKey {
    /** 来源元素（卡片）的 key；null = 普通侧推 */
    public val mochiSource: String? get() = null
}

/** 当前内容所在的 [MochiNavHost] 的返回栈（应用自己创建、传给宿主的那个），不在里面时是 null。
 *  深层页面、侧边栏里返回：`LocalMochiNavBackStack.current?.removeLast()` */
public val LocalMochiNavBackStack: ProvidableCompositionLocal<NavBackStack<NavKey>?> = staticCompositionLocalOf { null }

/**
 * 返回到栈里第 [index] 页（0 = 根，按栈的顺序；只用于 `MutableList` 语义下的清尾）。
 * 返回 false：index 不在栈里，或者已经在那一页
 */
public fun NavBackStack<NavKey>.mochiPopTo(index: Int): Boolean {
    if (index < 0 || index >= size - 1) return false
    subList(index + 1, size).clear()
    return true
}

/**
 * 当前页面的转场是否已经结束（打开动画走完、也没在关闭）。
 *
 * 转场期间页面按整屏大小排好版、只做缩放，布局本身是稳定的；但重的内容（网络请求、大图解码）最好等它变成 true
 * 再开始，期间用 [ui.feice.mochi.basic.MochiSkeleton] 占位——骨架和真内容占一样的位置，加载完成时原地交叉淡化，
 * 转场过程中不会有东西跳动。不在 [MochiNavHost] 里时总是 true。
 */
@Composable
public fun rememberMochiNavSettled(): State<Boolean> {
    val e = LocalMochiNavEntry.current
    return remember(e) { derivedStateOf { e == null || (!e.inTransition && !e.closing) } }
}
