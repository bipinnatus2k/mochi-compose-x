// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 逻辑栈（Navigation 3 backStack）→ 可视条目的编排测试：push 掉头、pop 排队、popTo 群退、
 * 关完清理。视觉栈滞后于逻辑栈是这套转场的关键行为，全部在 sync 的接口上验证（见 MochiNavSync.kt）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MochiNavSyncTest {
    @Test
    fun pushCreatesAnOpenVisualAndPopClosesItWithoutRemovingTheLogicalPage() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("detail"))
        var visuals = nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home"), TestKey("detail")), nav.stack.toList())
        assertEquals(2, visuals.size)
        val detail = visuals.last()
        assertFalse(detail.closing)
        assertEquals(0f, detail.progress.value, 0f)

        nav.pop()
        visuals = nav.syncAll()
        // 逻辑栈已经少了 detail，可视条目还在（closing，等退场动画演完）
        assertEquals(listOf<NavKey>(TestKey("home")), nav.stack.toList())
        assertEquals(2, nav.entries.size)
        assertTrue(detail.closing)

        // 关完：可视条目摘掉，没有排队的返回
        nav.onClosed(detail)
        assertEquals(1, nav.entries.size)
    }

    @Test
    fun pushingTheSameKeyWhileItClosesReversesInPlace() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("detail", mochiSource = "card"))
        val detail = nav.syncAll().last()
        detail.progress.snapTo(0.4f)
        nav.pop()
        nav.syncAll()
        assertTrue(detail.closing)

        nav.push(TestKey("detail", mochiSource = "card"))
        nav.syncAll()
        // 不新开一页：同一个 key 掉头，进度和位置连续
        assertEquals(listOf<NavKey>(TestKey("home"), TestKey("detail", mochiSource = "card")), nav.stack.toList())
        assertEquals(2, nav.entries.size)
        assertFalse(detail.closing)
        assertEquals(0.4f, detail.progress.value, 0f)
    }

    @Test
    fun popWhileSourcePageIsClosingQueuesUntilItFinishes() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("page"))
        val page = nav.syncAll().last()
        nav.push(TestKey("detail", mochiSource = "card"))
        val detail = nav.syncAll().last()
        detail.progress.snapTo(0.5f)

        // 连续返回：先关「detail」（来源页，正在变形），再关 page——来源页还在变形，page 的返回排队
        nav.pop()
        nav.syncAll()
        assertTrue(detail.closing)
        nav.pop()
        nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home")), nav.stack.toList())
        assertFalse(page.closing)
        assertTrue(nav.pending.contains(page))

        // 「detail」关完：排队的返回接着关 page
        nav.onClosed(detail)
        assertTrue(page.closing)
        assertTrue(nav.pending.isEmpty())
        nav.onClosed(page)
        assertEquals(1, nav.entries.size)
    }

    @Test
    fun popToSlidesEveryPageOutFromItsCurrentPosition() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("first"))
        val first = nav.syncAll().last()
        first.progress.snapTo(0.8f)
        nav.push(TestKey("second"))
        val second = nav.syncAll().last()
        second.progress.snapTo(0.5f)

        nav.stack.mochiPopTo(0)
        nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home")), nav.stack.toList())
        assertEquals(3, nav.entries.size)
        assertTrue(first.closing)
        assertTrue(second.closing)

        nav.onClosed(first)
        nav.onClosed(second)
        assertEquals(1, nav.entries.size)
    }

    @Test
    fun popToWithSourcePageFinishesClosingsAndRemovesMiddlePagesAtOnce() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("first"))
        val first = nav.syncAll().last()
        nav.push(TestKey("detail", mochiSource = "card"))
        val detail = nav.syncAll().last()
        detail.progress.snapTo(0.3f)
        nav.pop() // 关「detail」（来源变形中）
        nav.syncAll()
        assertTrue(detail.closing)

        // popTo 只移走一页（detail 上一次 pop 已从 diff 里走掉）→ 按 pop 的形状排队：first 等来源页变形走完再退，
        // 不瞬间截断变形（比旧实现平滑；popTo 一次移走多页时才走群退 / 瞬移，见上一个测试）
        nav.stack.mochiPopTo(0)
        nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home")), nav.stack.toList())
        assertEquals(3, nav.entries.size)
        assertFalse(first.closing)
        assertTrue(nav.pending.contains(first))
        assertTrue(detail.closing)

        nav.onClosed(detail)
        assertTrue(first.closing)
        nav.onClosed(first)
        assertEquals(1, nav.entries.size)
    }

    @Test
    fun restoredStackGetsOpenVisualsOnFirstSync() = runBlocking {
        // 模拟进程重建：栈直接带着两页（opened 状态），引擎第一次 sync 就要补出可视条目，不演打开动画
        val nav = MochiNavEngine(NavBackStack<NavKey>(TestKey("home"), TestKey("detail")))
        val visuals = nav.syncAll()
        assertEquals(2, visuals.size)
        assertEquals(1f, visuals[0].progress.value, 0f)
        assertEquals(1f, visuals[1].progress.value, 0f)
    }

    @Test
    fun pushingAKeyAlreadyOnTheStackDoesNotOpenItTwice() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("detail"))
        nav.syncAll()
        nav.push(TestKey("detail"))
        val visuals = nav.syncAll()
        // 等值 key 即同一目的地：栈里两个等值元素只渲染一个可视条目（Nav3 语义，key 即条目身份）
        assertEquals(3, nav.stack.size)
        assertEquals(2, visuals.size)
    }
}
