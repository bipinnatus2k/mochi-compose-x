// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.demo

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import ui.feice.mochi.demo.catalog.catalog
import ui.feice.mochi.demo.pages.sharedBarTitle
import ui.feice.mochi.navigation.MochiNavKey

/** 演示 app 的页面 key（Navigation 3 的类型安全路由）：目录条目用 id，侧推 / 共享顶栏演示是带页码的数据类。 */
@Serializable
data object Home : NavKey

@Serializable
data object About : NavKey

@Serializable
data object GlassPreset : NavKey

@Serializable
data class PushStep(val page: Int) : NavKey

@Serializable
data class SharedBarStep(val step: Int) : NavKey

/** 目录卡片打开的页面：来源（[mochiSource]）就是卡片自己，「从卡片打开应用」式变形 */
@Serializable
data class CatalogPage(val id: String, override val mochiSource: String? = null) : MochiNavKey

/** 侧边栏演示（模拟聊天应用）：主聊天页和设置页 */
@Serializable
data object ChatRoot : NavKey

@Serializable
data object ChatSettings : NavKey

/** 网络面板演示：面板内的多页导航（根 / 安全性 / 高级设置 / IP 设置） */
@Serializable
data object SheetRoot : NavKey

@Serializable
data object SheetSecurity : NavKey

@Serializable
data object SheetAdvanced : NavKey

@Serializable
data object SheetIp : NavKey

/** key 在返回历史菜单里显示的标题 */
internal fun keyTitle(key: NavKey): String = when (key) {
    Home -> "首页"
    About -> "关于"
    GlassPreset -> "玻璃样式"
    is PushStep -> "第 ${key.page} 页"
    is SharedBarStep -> sharedBarTitle(key.step)
    is CatalogPage -> catalog.firstOrNull { it.id == key.id }?.title ?: key.id
    else -> key.toString()
}
