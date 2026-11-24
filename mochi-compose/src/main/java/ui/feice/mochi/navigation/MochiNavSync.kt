// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey

/**
 * 对比 Navigation 3 的逻辑栈（[MochiNavEngine.stack]）和可视条目，按 push / pop 的形状编排转场。
 * 每次场景内容组合时由 [MochiNavScene] 调一次；不动逻辑栈，只维护 [MochiNavEngine.entries]
 * （视觉栈滞后于逻辑栈：pop 的页先标记 closing、等退场动画演完才被 [MochiNavEngine.onClosed] 摘掉）。
 *
 * 条目一律从 [MochiNavEngine.entryLog]（宿主包一层 entryProvider 登记的、与本次重组同步的 Nav3 条目）按 key 取，
 * 不按场景对象捕获的列表定位：AnimatedContent 在 key 不变时会用旧场景实例再组合一次内容，
 * 那一刻栈可能已经变了，按位置配对会越界，按 key 配对永远和当前栈一致。
 *
 * 语义和旧的手搓栈一致：
 * - push 正在退出的同一页（同一个 key）：让它掉头，从当前位置和速度连续地回来，不新开一页；
 * - 侧推的页正在退出、又推入别的页：两个转场同时进行（退出的接着滑走，新页从右边进来）；
 *   涉及「从来源打开」的页没法同时变形，正在关闭的页直接结束；
 * - pop 赶上来源页还在变形：排队（[MochiNavEngine.pending]），上一页一关完就接着关（连续返回不被吞掉）；
 * - popTo（一次移走多页）：侧推页从各自当前位置一起退出；涉及来源页时中间的页直接移走、只让栈顶演退场；
 * - push 已在栈里的等值 key：不开新页（Navigation 3 的语义：等值 key 即同一目的地）。
 */
internal fun MochiNavEngine.sync() {
    val stackKeys = stack.toList()

    // 现存可视条目的 navEntry 换成最新装饰后的 Nav3 条目（关闭中的条目保留 pop 前那一条，退场画的是它）
    for (key in stackKeys) {
        entries.lastOrNull { !it.closing && it.key == key }?.let { v -> entryLog[key]?.let { v.navEntry = it } }
    }

    val old = lastKeys
    if (stackKeys != old) {
        lastKeys = stackKeys
        // 公共前缀之后的差异：push 是新增（转场里一般是 1 个），pop / popTo 是移除
        var p = 0
        while (p < old.size && p < stackKeys.size && old[p] == stackKeys[p]) p++
        val added = stackKeys.subList(p, stackKeys.size)
        val removed = old.subList(p, old.size)
        for (key in removed) {
            if (removed.size == 1) handlePop(key) else handlePopTo(key)
        }
        for (key in added) {
            handlePush(key)
        }
    }

    // 栈里还没有可视条目的位置补 opened = true 的条目，不演打开动画：宿主刚接上、应用进程重建恢复的栈都在这一步补齐；
    // push 刚开出的条目已经占位，不会重复补。等值 key 重复入栈时只补一次（同 key 视作同一目的地）
    for (key in stackKeys) {
        if (entries.none { !it.closing && it.key == key }) {
            val logged = requireNotNull(entryLog[key]) { "entryProvider 没有为栈上的 key 生成条目：$key" }
            entries += MochiNavEntry(key, logged.contentKey, opened = true).also { it.navEntry = logged }
        }
    }

    // 登记表里只剩栈上的 key；pop 出去的条目引用已被可视条目捕获，退场不需要登记表
    val keep = stackKeys.toHashSet()
    entryLog.keys.filter { it !in keep }.forEach { entryLog.remove(it) }
}

private fun MochiNavEngine.handlePush(key: NavKey) {
    // 正在退出的页、又要进同一页：直接让它掉头，从当前位置和速度连续地回来，不新开一页
    val leaving = entries.asReversed().takeWhile { it.closing }.firstOrNull { it.key == key }
    if (leaving != null) {
        leaving.closing = false
        leaving.navEntry = entryLog[key]
        return
    }
    // 来源转场没法并行：正在关闭的「从来源打开」页直接结束；带来源的新页推入时也一样（旧的那页瞬间收场）
    if ((key as? MochiNavKey)?.mochiSource != null || entries.any { it.closing && it.source != null }) {
        entries.removeAll { it.closing }
    }
    pending.clear()
    // 同一个 key 已经开着：不重复开（等值 key 即同一目的地）
    if (entries.any { !it.closing && it.key == key }) return
    val logged = requireNotNull(entryLog[key]) { "entryProvider 没有为栈上的 key 生成条目：$key" }
    entries += MochiNavEntry(key, logged.contentKey, opened = false).also { it.navEntry = logged }
}

private fun MochiNavEngine.handlePop(key: NavKey) {
    val v = entries.lastOrNull { !it.closing && it.key == key } ?: return
    val leavingTop = entries.lastOrNull()?.takeIf { it.closing }
    // 来源页还在变形（或退出的这页自己带来源）：排队，等它关完再关这一页，两个来源变形不会同时跑
    if (leavingTop != null && (leavingTop.source != null || v.source != null)) {
        pending += v
        return
    }
    v.closing = true
}

private fun MochiNavEngine.handlePopTo(key: NavKey) {
    // popTo 的差异一次移走多页：排队的返回作废，按 popTo 的形状处理整段退出。逐个 key 调进来是幂等的
    pending.clear()
    val targetKey = stack.lastOrNull() ?: return
    val ti = entries.indexOfLast { !it.closing && it.key == targetKey }
    if (ti < 0) return
    val exiting = entries.subList(ti + 1, entries.size)
    if (exiting.all { it.source == null }) {
        // 侧推页一起退出：各自从当前位置滑走（每页的 closing 由各自的 LaunchedEffect 接管）
        for (v in exiting) v.closing = true
    } else {
        // 涉及「从来源打开」的页：正在关闭的直接结束，中间的页瞬间移走，只让栈顶那一页演退场
        entries.removeAll { it.closing }
        val ti2 = entries.indexOfLast { !it.closing && it.key == targetKey }
        if (ti2 < 0) return
        while (entries.size > ti2 + 2) entries.removeAt(ti2 + 1)
        val v = entries.lastOrNull { !it.closing && it.key == key } ?: return
        if (v !in entries.subList(ti2 + 1, entries.size)) return
        v.closing = true
    }
}
