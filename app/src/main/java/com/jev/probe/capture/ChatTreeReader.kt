package com.jev.probe.capture

/** Node-only parsing, kept independent of Android for replaying captured layouts. */
internal object ChatTreeReader {
    enum class App(val label: String) { WECHAT("微信"), XHS("小红书") }
    data class Node(
        val parent: Int = -1,
        val id: String = "",
        val text: String = "",
        val description: String = "",
        val hint: String = "",
        val className: String = "",
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val editable: Boolean = false,
        val visible: Boolean = true,
        val scrollable: Boolean = false,
        val forward: Boolean = false
    )

    data class Message(val side: String, val text: String, val node: Node)
    data class Result(val inChat: Boolean, val messages: List<Message>, val atLatest: Boolean, val diagnostic: String)

    private val hints = listOf("输入消息", "发消息", "发送消息", "说点什么", "按住说话", "按住录音",
        "切换到键盘", "切换到语音", "切换语音输入", "切换文字输入", "切换到文字输入")
    private val controls = listOf("表情", "更多功能", "添加功能", "发送", "切换到键盘", "切换到语音",
        "切换语音输入", "切换文字输入", "切换到文字输入")
    private val time = Regex("""(?:\d{1,2}[:：]\d{2}|(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\d{1,4}[年/.-]\d{1,2}[月/.-]\d{1,2}日?)(?:\s*\d{1,2}[:：]\d{2})?)""")

    fun read(nodes: List<Node>, width: Int, height: Int, app: App = App.WECHAT): Result {
        val visible = nodes.indices.filter {
            val n = nodes[it]
            n.visible && n.right > n.left && n.bottom > n.top &&
                n.bottom > 0 && n.top < height && n.right > 0 && n.left < width
        }
        fun label(n: Node) = listOf(n.text, n.description, n.hint).joinToString(" ").replace(" ", "")
        fun isInput(n: Node) = n.editable || n.className.endsWith("EditText")
        val inputs = visible.filter { i ->
            val n = nodes[i]
            val name = label(n)
            n.top > height * 0.30f && n.bottom - n.top < height * 0.18f &&
                !name.contains("搜索") && !n.id.contains("search", ignoreCase = true) &&
                (isInput(n) || hints.any { name.contains(it) })
        }
        val toolbar = visible.filter { i ->
            val n = nodes[i]
            n.top > height * 0.60f && n.bottom - n.top < height * 0.10f &&
                n.right - n.left < width * 0.35f && controls.any { label(n).contains(it) }
        }
        val composerTop = inputs.minOfOrNull { nodes[it].top }
            ?: toolbar.takeIf { it.size >= 2 }?.minOf { nodes[it].top } ?: (height * 0.92f).toInt()
        val lists = visible.filter { i ->
            val n = nodes[i]
            n.bottom - n.top > height * 0.18f && n.top >= height * 0.05f &&
                n.top < composerTop && n.bottom <= composerTop + height * 0.08f &&
                (n.scrollable || n.className.endsWith("RecyclerView") || n.className.endsWith("ListView"))
        }.toSet()
        fun within(index: Int, ancestor: Int): Boolean {
            var p = index
            while (p in nodes.indices) {
                if (p == ancestor) return true
                p = nodes[p].parent
            }
            return false
        }
        fun row(index: Int, list: Int): Int {
            var p = index
            while (p in nodes.indices && nodes[p].parent != list) p = nodes[p].parent
            return p
        }
        val directParents = nodes.map { it.parent }.toSet()
        fun body(n: Node): String = n.text.trim().ifEmpty {
            if (n.className.endsWith("TextView") || n.className.endsWith("View")) n.description.trim() else ""
        }
        fun side(n: Node): String = if (kotlin.math.abs(width * 0.13f - n.left) <=
            kotlin.math.abs(width * 0.87f - n.right)) "other" else "me"
        val candidates = ArrayList<Message>()
        val known = if (app == App.WECHAT) visible.filter {
            nodes[it].id == "com.tencent.mm:id/bkl" && nodes[it].top > height * 0.10f && nodes[it].bottom <= composerTop
        } else emptyList()
        val chatDetails = visible.any {
            val n = nodes[it]
            n.top < height * 0.18f && n.left > width * 0.65f &&
                listOf("聊天信息", "聊天详情", "聊天设置").any { label(n).contains(it) }
        }
        val chatShell = inputs.isNotEmpty() || toolbar.size >= 2 || known.isNotEmpty() || chatDetails
        if (chatShell) {
            // Preserve the 1.6.0 rule, including versions with nested body text.
            known.forEach { index ->
                val n = nodes[index]
                val own = body(n)
                if (own.isNotEmpty()) candidates.add(Message(side(n), own, n))
                else visible.filter { it != index && within(it, index) && it !in directParents }
                    .map { body(nodes[it]) }.filter { it.isNotBlank() }.joinToString("\n")
                    .takeIf { it.isNotEmpty() }?.let { candidates.add(Message(side(n), it, n)) }
            }
            // Unknown resource IDs are accepted only inside a message list row
            // with an avatar. Titles, list previews and the composer stay out.
            visible.filter { it !in directParents }.forEach { index ->
                val n = nodes[index]
                val text = body(n)
                if (n.editable || n.className.endsWith("EditText") || text.isBlank() ||
                    text == "已读" || text == "未读" || time.matches(text) ||
                    n.description.contains("头像") || known.any { within(index, it) } ||
                    n.top < height * 0.12f || n.bottom > composerTop ||
                    n.right - n.left >= width * 0.86f) return@forEach
                val list = lists.firstOrNull { within(index, it) } ?: return@forEach
                val rowIndex = row(index, list)
                val avatar = visible.firstOrNull { j ->
                    val a = nodes[j]
                    val avatarLabel = a.description.contains("头像") || a.id.contains("avatar", true) ||
                        a.id.contains("head_icon", true)
                    val avatarShape = app == App.XHS && a.className.endsWith("ImageView") &&
                        a.right - a.left in (width * 0.04f).toInt()..(width * 0.16f).toInt() &&
                        kotlin.math.abs((a.right - a.left) - (a.bottom - a.top)) < width * 0.03f
                    j != index && row(j, list) == rowIndex && (avatarLabel || avatarShape) &&
                        (a.left + a.right < width * 0.50f || a.left + a.right > width * 1.50f)
                }?.let { nodes[it] }
                if (avatar == null) {
                    // Some XHS versions expose a text container but no avatar.
                    // Require a message-specific ID inside the scrolling list.
                    val messageId = listOf("message_content", "msg_content", "chat_content", "content_text")
                        .any { n.id.contains(it, true) }
                    if (app == App.XHS && messageId && n.left > width * 0.07f && n.right < width * 0.93f) {
                        candidates.add(Message(side(n), text, n))
                    }
                    return@forEach
                }
                if (n.bottom <= avatar.top + (avatar.bottom - avatar.top) * 0.45f ||
                    n.top < avatar.top - height * 0.01f) return@forEach
                val sender = if (avatar.left + avatar.right > width) "me" else "other"
                if (sender == "me" && n.right > avatar.left || sender == "other" && n.left < avatar.right) return@forEach
                candidates.add(Message(sender, text, n))
            }
        }
        val messages = candidates.sortedBy { it.node.top }.distinctBy {
            listOf(it.side, it.text, it.node.left, it.node.top, it.node.right, it.node.bottom)
        }
        val inChat = inputs.isNotEmpty() || toolbar.size >= 2 || known.isNotEmpty() || chatDetails && messages.isNotEmpty()
        val diagnostic = "${app.label}无障碍：总节点 ${nodes.size} · 可见节点 ${visible.size} · 输入栏 ${inputs.size} · 输入工具 ${toolbar.size} · 消息列表 ${lists.size} · 旧版消息节点 ${known.size} · 正文 ${messages.size} 条"
        return Result(inChat, messages, lists.none { nodes[it].forward }, diagnostic)
    }
}
