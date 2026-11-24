// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.demo.about.AboutPage
import ui.feice.mochi.demo.catalog.CatalogGrid
import ui.feice.mochi.demo.catalog.CatalogGroup
import ui.feice.mochi.demo.catalog.catalog
import ui.feice.mochi.demo.common.DemoIcons
import ui.feice.mochi.demo.common.Glyph
import ui.feice.mochi.demo.pages.PushStepPage
import ui.feice.mochi.demo.pages.SharedBarStepPage
import ui.feice.mochi.demo.theme.GlassPresetPage
import ui.feice.mochi.demo.theme.ThemeTab
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.MochiTabScaffold
import ui.feice.mochi.theme.ThemeController

/** 演示 app。页面 key：目录条目的 id；侧推演示是 [PushStep]，共享顶栏演示是 [SharedBarStep]（见 DemoNav.kt）。 */
@Composable
fun DemoApp(controller: ThemeController, modifier: Modifier = Modifier) {
    val stack = rememberNavBackStack(Home)
    ProvideMochiOverscroll {
        MochiNavHost(
            backStack = stack,
            modifier = modifier,
            predictiveBack = LocalDemoAppearance.current?.predictiveBack ?: true,
            entryProvider = entryProvider {
                entry<Home> { HomeScreen(controller) }
                entry<About> { AboutPage() }
                entry<GlassPreset> { GlassPresetPage() }
                entry<PushStep> { key -> PushStepPage(key.page) }
                entry<SharedBarStep> { key -> SharedBarStepPage(key.step) }
                entry<CatalogPage> { key -> catalog.first { it.id == key.id }.let { it.page(it) } }
            },
        )
    }
}

@Composable
private fun HomeScreen(controller: ThemeController, modifier: Modifier = Modifier) {
    val tabs = remember {
        persistentListOf(
            MochiTabItem("组件", selectedIcon = {
                Glyph(DemoIcons.GRID, it, size = 22, filled = true)
            }) { Glyph(DemoIcons.GRID, it, size = 22) },
            MochiTabItem("效果", selectedIcon = {
                Glyph(DemoIcons.EFFECTS, it, size = 22, filled = true)
            }) { Glyph(DemoIcons.EFFECTS, it, size = 22) },
            MochiTabItem("导航", selectedIcon = {
                Glyph(DemoIcons.NAVIGATION, it, size = 22, filled = true)
            }) { Glyph(DemoIcons.NAVIGATION, it, size = 22) },
            MochiTabItem("主题", selectedIcon = {
                Glyph(DemoIcons.PALETTE, it, size = 22, filled = true)
            }) { Glyph(DemoIcons.PALETTE, it, size = 22) },
        )
    }
    MochiTabScaffold(
        items = tabs,
        collapseOnScroll = false,
        modifier = modifier,
        behavior = MochiTabBarDefaults.iconOnlyBehavior(selectedLabelOnly = true),
    ) { page ->
        when (page) {
            0 -> CatalogGrid("组件", catalog.filter { it.group == CatalogGroup.Components })
            1 -> CatalogGrid("效果", catalog.filter { it.group == CatalogGroup.Effects })
            2 -> CatalogGrid("导航", catalog.filter { it.group == CatalogGroup.Navigation })
            else -> ThemeTab(controller)
        }
    }
}
