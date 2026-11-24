// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.screenshot

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.theme.MochiGlassDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.lightColorScheme
import ui.feice.mochi.theme.mochiGlassMaterials

/**
 * 页面侧推转场里懒加载分组列表的逐帧截图（KernelSU 授权日志页的结构：多行副标题、数据晚一点到）。
 * 输出 build/outputs/menu-motion/nav-list/NNN.png，找转场中行被裁短、行间露缝的帧。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class NavListMotionTest {
    // 逐帧调试用，没有基准图：只在录制模式（-Proborazzi.test.record=true）下跑，CI 的比对（verify）跳过
    @org.junit.Before fun onlyWhenRecording() {
        org.junit.Assume.assumeFalse(System.getProperty("roborazzi.test.verify") == "true")
    }

    @get:Rule val rule = createComposeRule()

    @Test fun pushAndPop() {
        lateinit var stack: androidx.navigation3.runtime.NavBackStack<NavKey>
        rule.setContent {
            MochiTheme(
                colors = lightColorScheme(),
                systemBarAppearance = false,
                materials = mochiGlassMaterials(MochiGlassDefaults.Regular),
            ) {
                stack = remember { NavBackStack<NavKey>(Root) }
                MochiNavHost(
                    backStack = stack,
                    entryProvider = entryProvider {
                        entry<Root> {
                            MochiLazyCompactTitleScaffold(title = "Root") {
                                mochiListSection("apps") {
                                    rows((0 until 12).toList(), key = {
                                        it
                                    }) { i -> MochiListRow("App $i", subtitle = "com.example.app$i") }
                                }
                            }
                        }
                        entry<SuLog> {
                            var entries by remember { mutableStateOf(emptyList<Int>()) }
                            // 日志晚一点读出来（KernelSU 里是 root shell 读文件）
                            LaunchedEffect(Unit) {
                                delay(120)
                                entries = (0 until 20).toList()
                            }
                            MochiLazyCompactTitleScaffold(title = "SU log", navigationIcon = { MochiBackButton() }) {
                                mochiListSection("entries") {
                                    rows(entries, key = { it }) { i ->
                                        MochiListRow(
                                            "sh",
                                            subtitle = "cat /data/adb/ksu/log/sulog-2026-11-17.log\nPID 27$i  UID 0",
                                            value = "2026-11-17",
                                            onClick = {},
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { stack.add(SuLog) }
        for (i in 0 until 40) {
            rule.mainClock.advanceTimeBy(16)
            if (i % 2 == 0 || i < 12) captureScreenRoboImage("build/outputs/menu-motion/nav-list/%03d.png".format(i))
        }
        rule.runOnIdle { stack.removeAt(stack.lastIndex) }
        for (i in 0 until 30) {
            rule.mainClock.advanceTimeBy(16)
            if (i % 2 == 0) captureScreenRoboImage("build/outputs/menu-motion/nav-list/%03d.png".format(100 + i))
        }
    }
}

private data object Root : NavKey

private data object SuLog : NavKey
