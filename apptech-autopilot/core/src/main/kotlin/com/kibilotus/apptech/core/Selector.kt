package com.kibilotus.apptech.core

/** UI 노드를 찾는 조건. 앱 업데이트로 문구가 바뀌어도 대응할 수 있게 [or]로 후보를 여러 개 둘 수 있다. */
fun interface Selector {
    fun matches(node: UiNode): Boolean

    fun findIn(root: UiNode): UiNode? = root.walk().firstOrNull { it.visible && !it.bounds.isEmpty && matches(it) }

    infix fun or(other: Selector): Selector = Named("$this | $other") { matches(it) || other.matches(it) }

    private class Named(private val name: String, private val predicate: (UiNode) -> Boolean) : Selector {
        override fun matches(node: UiNode) = predicate(node)
        override fun toString() = name
    }

    companion object {
        /** 라벨(text 또는 desc)이 정확히 일치 (앞뒤 공백 무시) */
        fun text(value: String): Selector = Named("text='$value'") { n ->
            n.text?.trim() == value || n.desc?.trim() == value
        }

        /** 라벨에 부분 문자열 포함 */
        fun contains(value: String): Selector = Named("contains='$value'") { n -> n.label.contains(value) }

        /** 라벨이 정규식에 매치 (부분 매치) */
        fun regex(pattern: String): Selector {
            val re = Regex(pattern)
            return Named("regex=/$pattern/") { n -> n.label.isNotEmpty() && re.containsMatchIn(n.label) }
        }

        /** 리소스 ID (패키지 접두어 생략 가능: "tab_benefit" 또는 "pkg:id/tab_benefit") */
        fun id(value: String): Selector = Named("id=$value") { n ->
            val id = n.viewId ?: return@Named false
            id == value || id.substringAfter(":id/") == value
        }
    }
}
