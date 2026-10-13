package com.kibilotus.apptech.core

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    override fun toString() = "[$left,$top][$right,$bottom]"
}

/**
 * 화면 UI 트리의 불변 스냅샷.
 *
 * 플랫폼(AccessibilityNodeInfo 등)에 의존하지 않으므로 코어 로직을 JVM에서 테스트할 수 있다.
 * [handle]에는 플랫폼 원본 객체를 담아 Driver가 클릭 폴백 등에 사용한다.
 */
class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val children: List<UiNode> = emptyList(),
    val handle: Any? = null,
) {
    var parent: UiNode? = null
        private set

    init {
        children.forEach { it.parent = this }
    }

    /** text와 contentDescription을 합친 라벨. WebView 화면은 텍스트가 desc에만 있는 경우가 많다. */
    val label: String get() = listOfNotNull(text, desc).filter { it.isNotBlank() }.joinToString(" ")

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }

    /** 텍스트 노드 자체는 클릭 불가인 경우가 많으므로, 가장 가까운 클릭 가능한 조상을 찾는다. */
    fun clickTarget(): UiNode =
        generateSequence(this) { it.parent }.firstOrNull { it.clickable && it.enabled && !it.bounds.isEmpty } ?: this

    /** 실패 분석용 텍스트 덤프 (보이는 노드만, 라벨/ID가 있는 노드 위주) */
    fun dump(): String = buildString {
        fun rec(n: UiNode, depth: Int) {
            if (!n.visible) return
            val flags = buildString {
                if (n.clickable) append('C')
                if (n.scrollable) append('S')
                if (!n.enabled) append('D')
            }
            append("  ".repeat(depth))
            append(n.className?.substringAfterLast('.') ?: "?")
            if (n.label.isNotEmpty()) append(" \"").append(n.label.replace("\n", "\\n")).append('"')
            if (n.viewId != null) append(" #").append(n.viewId.substringAfter(":id/"))
            if (flags.isNotEmpty()) append(" {").append(flags).append('}')
            append(' ').append(n.bounds).append('\n')
            n.children.forEach { rec(it, depth + 1) }
        }
        rec(this@UiNode, 0)
    }
}
