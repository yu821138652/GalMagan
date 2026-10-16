package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ChatTreeReaderTest {
    private fun node(parent: Int = 0, text: String = "", description: String = "",
                     id: String = "", cls: String = "android.widget.TextView",
                     left: Int = 140, top: Int = 400, right: Int = 650, bottom: Int = 460,
                     editable: Boolean = false, forward: Boolean = false) = ChatTreeReader.Node(
        parent = parent, text = text, description = description, id = id, className = cls,
        left = left, top = top, right = right, bottom = bottom,
        editable = editable, forward = forward
    )

    private fun base() = mutableListOf(
        node(parent = -1, cls = "android.widget.FrameLayout", left = 0, top = 0, right = 1000, bottom = 2000),
        node(cls = "android.widget.EditText", left = 140, top = 1800, right = 800, bottom = 1900, editable = true),
        node(cls = "androidx.recyclerview.widget.RecyclerView", left = 0, top = 200, right = 1000, bottom = 1790)
    )

    @Test fun keepsVersion160TextAndSenderRules() {
        val nodes = base()
        nodes += node(parent = 2, id = "com.tencent.mm:id/bkl", text = "收到，晚上见", left = 140)
        nodes += node(parent = 2, id = "com.tencent.mm:id/bkl", text = "好", left = 740, top = 600, right = 870, bottom = 660)
        val result = ChatTreeReader.read(nodes, 1000, 2000)
        assertEquals(listOf("收到，晚上见", "好"), result.messages.map { it.text })
        assertEquals(listOf("other", "me"), result.messages.map { it.side })
    }

    @Test fun readsNestedTextInOldContainerOnlyOnce() {
        val nodes = base()
        nodes += node(parent = 2, id = "com.tencent.mm:id/bkl", cls = "android.widget.FrameLayout")
        nodes += node(parent = 3, text = "嵌套正文")
        assertEquals(listOf("嵌套正文"), ChatTreeReader.read(nodes, 1000, 2000).messages.map { it.text })
    }

    @Test fun readsChangedIdInsideAvatarRow() {
        val nodes = base()
        nodes += node(parent = 2, cls = "android.widget.LinearLayout", left = 0, right = 1000, top = 380, bottom = 500)
        nodes += node(parent = 3, cls = "android.widget.ImageView", description = "小明的头像", left = 40, right = 120, top = 380, bottom = 470)
        nodes += node(parent = 3, id = "com.tencent.mm:id/new_body", text = "新版本正文")
        assertEquals(listOf("新版本正文"), ChatTreeReader.read(nodes, 1000, 2000).messages.map { it.text })
    }

    @Test fun senderComesFromAvatarForLongOutgoingBubble() {
        val nodes = base()
        nodes += node(parent = 2, cls = "android.widget.LinearLayout", left = 0, right = 1000, top = 380, bottom = 500)
        nodes += node(parent = 3, cls = "android.widget.ImageView", description = "我的头像", left = 880, right = 960, top = 380, bottom = 470)
        nodes += node(parent = 3, text = "很长的我方消息", left = 150, right = 860)
        assertEquals("me", ChatTreeReader.read(nodes, 1000, 2000).messages.single().side)
    }

    @Test fun doesNotReadConversationListWithoutComposer() {
        val nodes = base()
        nodes[1] = nodes[1].copy(top = 60, bottom = 140)
        nodes += node(parent = 2, text = "列表预览")
        val result = ChatTreeReader.read(nodes, 1000, 2000)
        assertFalse(result.inChat)
        assertTrue(result.messages.isEmpty())
    }

    @Test fun excludesTitleInputAndUnanchoredText() {
        val nodes = base()
        nodes += node(text = "会话标题", top = 70, bottom = 140)
        nodes += node(parent = 2, text = "不能确定是消息的文字")
        assertTrue(ChatTreeReader.read(nodes, 1000, 2000).messages.isEmpty())
    }

    @Test fun hiddenNodesAreNeverImported() {
        val nodes = base()
        nodes += node(parent = 2, text = "隐藏的列表正文", id = "com.tencent.mm:id/bkl").copy(visible = false)
        assertTrue(ChatTreeReader.read(nodes, 1000, 2000).messages.isEmpty())
    }

    @Test fun repeatedMessagesAtDifferentPositionsArePreserved() {
        val nodes = base()
        nodes += node(parent = 2, text = "好", id = "com.tencent.mm:id/bkl")
        nodes += node(parent = 2, text = "好", id = "com.tencent.mm:id/bkl", top = 600, bottom = 660)
        assertEquals(2, ChatTreeReader.read(nodes, 1000, 2000).messages.size)
    }

    @Test fun olderPageIsNotMarkedAsLatest() {
        val nodes = base()
        nodes[2] = nodes[2].copy(forward = true)
        assertFalse(ChatTreeReader.read(nodes, 1000, 2000).atLatest)
    }

    @Test fun voiceComposerCanIdentifyEmptyChatWithDiagnostics() {
        val nodes = base()
        nodes[1] = nodes[1].copy(editable = false, className = "android.widget.Button", description = "按住 说话")
        val result = ChatTreeReader.read(nodes, 1000, 2000)
        assertTrue(result.inChat)
        assertTrue(result.messages.isEmpty())
        assertTrue(result.diagnostic.contains("正文 0 条"))
    }

    @Test fun knownMessagesDoNotRequireExposedComposer() {
        val nodes = base()
        nodes[1] = nodes[1].copy(visible = false)
        nodes += node(parent = 2, id = "com.tencent.mm:id/bkl", text = "输入栏不可见的正文")
        val result = ChatTreeReader.read(nodes, 1000, 2000)
        assertTrue(result.inChat)
        assertEquals("输入栏不可见的正文", result.messages.single().text)
    }

    @Test fun fullWidthComposerAndKeyboardRaisedComposerAreAccepted() {
        val nodes = base()
        nodes[1] = nodes[1].copy(left = 0, right = 1000, top = 700, bottom = 780)
        nodes[2] = nodes[2].copy(bottom = 690)
        nodes += node(parent = 2, id = "com.tencent.mm:id/bkl", text = "键盘上方的正文")
        assertTrue(ChatTreeReader.read(nodes, 1000, 2000).inChat)
        assertEquals(1, ChatTreeReader.read(nodes, 1000, 2000).messages.size)
    }

    @Test fun voiceComposerIgnoresSpacingVariants() {
        val nodes = base()
        nodes[1] = nodes[1].copy(editable = false, className = "android.widget.Button", text = "按住  说话")
        assertTrue(ChatTreeReader.read(nodes, 1000, 2000).inChat)
    }

    @Test fun singleRootReportsEmptyTreeWithoutInventingMessages() {
        val result = ChatTreeReader.read(listOf(base().first().copy(visible = false)), 1000, 2000)
        assertFalse(result.inChat)
        assertTrue(result.messages.isEmpty())
        assertTrue(result.diagnostic.contains("总节点 1"))
    }

    @Test fun searchBoxInLowerScreenIsNotAComposer() {
        val nodes = base()
        nodes[1] = nodes[1].copy(hint = "搜索联系人", id = "app:id/search")
        assertFalse(ChatTreeReader.read(nodes, 1000, 2000).inChat)
    }

    @Test fun xhsReadsNodeTextAndSenderBeforeOcr() {
        val nodes = base()
        nodes += node(parent = 2, cls = "android.widget.LinearLayout", left = 0, right = 1000, top = 380, bottom = 500)
        nodes += node(parent = 3, cls = "android.widget.ImageView", description = "头像", left = 40, right = 120, top = 380, bottom = 470)
        nodes += node(parent = 3, text = "节点中完整的文字")
        nodes += node(parent = 2, cls = "android.widget.LinearLayout", left = 0, right = 1000, top = 580, bottom = 700)
        nodes += node(parent = 6, cls = "android.widget.ImageView", description = "头像", left = 880, right = 960, top = 580, bottom = 670)
        nodes += node(parent = 6, text = "我方回复", left = 600, right = 860, top = 600, bottom = 660)
        val result = ChatTreeReader.read(nodes, 1000, 2000, ChatTreeReader.App.XHS)
        assertEquals(listOf("节点中完整的文字", "我方回复"), result.messages.map { it.text })
        assertEquals(listOf("other", "me"), result.messages.map { it.side })
    }

    @Test fun xhsAvatarWithoutDescriptionCanAnchorMessages() {
        val nodes = base()
        nodes += node(parent = 2, cls = "android.widget.LinearLayout", left = 0, right = 1000, top = 380, bottom = 500)
        nodes += node(parent = 3, cls = "android.widget.ImageView", left = 40, right = 120, top = 380, bottom = 460)
        nodes += node(parent = 3, description = "正文也可以在内容描述中")
        val result = ChatTreeReader.read(nodes, 1000, 2000, ChatTreeReader.App.XHS)
        assertEquals("正文也可以在内容描述中", result.messages.single().text)
    }

    @Test fun xhsCanvasMessagesRemainEmptyForOcrFallback() {
        val result = ChatTreeReader.read(base(), 1000, 2000, ChatTreeReader.App.XHS)
        assertTrue(result.inChat)
        assertTrue(result.messages.isEmpty())
    }

    @Test fun xhsMessageIdsCanAnchorTextWithoutAvatar() {
        val nodes = base()
        nodes += node(parent = 2, id = "com.xingin.xhs:id/message_content", text = "无头像的正文")
        nodes += node(parent = 2, text = "列表中不确定用途的文字", top = 600, bottom = 660)
        assertEquals(listOf("无头像的正文"),
            ChatTreeReader.read(nodes, 1000, 2000, ChatTreeReader.App.XHS).messages.map { it.text })
    }

    @Test fun xhsConversationPreviewWithoutComposerIsRejected() {
        val nodes = base()
        nodes[1] = nodes[1].copy(visible = false)
        nodes += node(parent = 2, id = "com.xingin.xhs:id/message_content", text = "列表预览")
        val result = ChatTreeReader.read(nodes, 1000, 2000, ChatTreeReader.App.XHS)
        assertFalse(result.inChat)
        assertTrue(result.messages.isEmpty())
    }
}
