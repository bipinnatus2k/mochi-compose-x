// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavTransitionRenderingTest {
    @Test
    fun sidePushMovesThePageWithoutChangingItsLocalClip() = runBlocking {
        val nav = engineOf(TestKey("home"))
        val geometry = NavGeometry(nav).apply {
            size = Size(320f, 640f)
            defaultCorners = MochiScreenCorners(16f, 20f, 0f, 0f)
        }
        nav.push(TestKey("detail"))
        val page = nav.syncAll().last()
        val initial = NavPageOutline(Rect(0f, 0f, 320f, 640f), 16f, 20f, 0f, 0f)
        assertEquals(initial, geometry.slideOutline(page))
        assertEquals(320f, geometry.slideX(page), 0f)

        page.progress.snapTo(0.5f)
        assertEquals(initial, geometry.slideOutline(page))
        assertEquals(160f, geometry.slideX(page), 0f)

        page.progress.snapTo(1f)
        assertNull(geometry.slideOutline(page))
    }

    @Test
    fun replacingAnExitingSlideKeepsTheUnderlyingPageInPlace() = runBlocking {
        val nav = engineOf(TestKey("home"))
        val geometry = NavGeometry(nav).apply { size = Size(320f, 640f) }
        val home = nav.syncAll().single()
        nav.push(TestKey("first"))
        val first = nav.syncAll().last()
        first.progress.snapTo(0.8f)
        nav.pop()
        nav.syncAll()

        val beforeReplacement = geometry.slideX(home)
        nav.push(TestKey("second"))
        assertEquals(beforeReplacement, geometry.slideX(home), 0f)
        val second = nav.syncAll().last()
        second.progress.snapTo(0.6f)
        first.progress.snapTo(0f)

        val beforeRemoval = geometry.slideX(home)
        nav.onClosed(first)
        assertEquals(-320f * SlideUnderShift * 0.6f, beforeRemoval, 0.001f)
        assertEquals(beforeRemoval, geometry.slideX(home), 0f)
    }

    @Test
    fun consecutivePopsKeepSlidesAliveAndSameRouteCanReverseBehindAnExit() = runBlocking {
        val nav = engineOf(TestKey("home"))
        nav.push(TestKey("first"))
        val first = nav.syncAll().last()
        first.progress.snapTo(0.7f)
        nav.push(TestKey("second"))
        val second = nav.syncAll().last()
        second.progress.snapTo(0.5f)

        assertTrue(nav.stack.mochiPopTo(0))
        nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home")), nav.stack.toList())
        assertTrue(first.closing)
        assertTrue(second.closing)
        nav.push(TestKey("first"))
        nav.syncAll()
        assertEquals(listOf<NavKey>(TestKey("home"), TestKey("first")), nav.stack.toList())
        assertEquals(3, nav.entries.size)
        assertFalse(first.closing)
        assertTrue(second.closing)
        assertEquals(0.7f, first.progress.value, 0f)
    }

    @Test
    fun sourceCaptureIsLimitedToTheVisibleGlassOverlay() = runBlocking {
        val nav = engineOf(TestKey("home"))
        val geometry = NavGeometry(nav)
        val home = nav.syncAll().single()
        nav.push(TestKey("detail", mochiSource = "card"))
        val page = nav.syncAll().last()

        assertFalse(geometry.needsCover(page))
        home.coverReaderCount++
        assertTrue(geometry.needsCover(page))
        assertFalse(geometry.needsCover(home))

        page.progress.snapTo(0.6f)
        assertFalse(geometry.needsCover(page))

        page.progress.snapTo(0.2f)
        assertTrue(geometry.needsCover(page))
        home.coverReaderCount--
        assertFalse(geometry.needsCover(page))
    }
}

/** 测试用的 key：route 就是身份（Navigation 3 语义），来源走 MochiNavKey */
internal data class TestKey(val route: String, override val mochiSource: String? = null) : MochiNavKey

/** 建一个引擎并做第一轮 sync（栈上的位置补 opened = true 的可视条目，模拟宿主接上） */
internal fun engineOf(vararg keys: NavKey): MochiNavEngine = MochiNavEngine(NavBackStack<NavKey>(*keys)).also { it.syncAll() }

/** 用栈当前位置的空条目跑一轮 sync（真实宿主里宿主包一层 entryProvider 把条目登记进 entryLog） */
internal fun MochiNavEngine.syncAll(): List<MochiNavEntry> {
    for (key in stack) entryLog[key] = NavEntry<NavKey>(key) { }
    sync()
    return entries.toList()
}
