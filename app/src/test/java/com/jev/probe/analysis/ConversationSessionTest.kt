package com.jev.probe.analysis

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.LogEntry
import com.jev.probe.core.kb.StyleProfile
import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    @Test
    fun sameContactAndSameMessagesKeepOrderedAdjustmentTurns() {
        val session = boundSession()
        session.add("先纠正事实")
        session.add("再补充措辞偏好")
        assertFalse(session.bind("contact-a|com.tencent.mm", snapshot().signature()))
        assertEquals(listOf("先纠正事实", "再补充措辞偏好"), session.adjustments)
    }

    @Test
    fun newVisibleMessageClearsAdjustmentsAndReportsDiscard() {
        val session = boundSession()
        session.add("只针对旧消息的补充")
        val changed = snapshot().copy(messages = snapshot().messages + Msg("other", "新收到的消息"))
        assertTrue(session.bind("contact-a|com.tencent.mm", changed.signature()))
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun changedSpeakerWithSameTextInvalidatesOldAdjustments() {
        val session = boundSession()
        session.add("原先以为是对方发的")
        val changed = snapshot().copy(messages = listOf(Msg("me", "周末有空吗？")))
        assertTrue(session.bind("contact-a|com.tencent.mm", changed.signature()))
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun switchingContactClearsAdjustmentsEvenWithIdenticalVisibleMessages() {
        val session = boundSession()
        session.add("联系人甲的背景")
        assertTrue(session.bind("contact-b|com.tencent.mm", snapshot().signature()))
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun switchingEnvironmentClearsAdjustmentsForSameContact() {
        val session = boundSession()
        session.add("针对微信窗口的补充")
        assertTrue(session.bind("contact-a|com.tencent.mobileqq", snapshot().signature()))
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun bindingWithoutAdjustmentsDoesNotReportDiscard() {
        val session = ConversationSession()
        assertFalse(session.bind("contact-a", "signature-a"))
        assertFalse(session.bind("contact-b", "signature-b"))
        session.add("现在添加补充")
        assertFalse(session.bind("contact-b", "signature-b"))
        assertEquals(listOf("现在添加补充"), session.adjustments)
    }

    @Test
    fun everyDirectionCanBeSelectedAndEntersTheNextInput() {
        val session = boundSession()
        assertEquals(ChatDirection.DEFAULT, session.direction)
        assertEquals(setOf("DEFAULT", "SOOTHE", "CONTINUE", "CONNECT"), ChatDirection.values().map { it.name }.toSet())
        ChatDirection.values().forEach { direction ->
            session.direction = direction
            val input = ConversationInput.build(snapshot(), null, "com.tencent.mm", "朋友", session.direction, session.adjustments)
            assertEquals(direction, input.direction)
            val encoded = input.json(includeStyle = false).getJSONObject("direction")
            assertEquals(direction.label, encoded.getString("name"))
            assertEquals(direction.guidance, encoded.getString("guidance"))
        }
    }

    @Test
    fun clearRemovesAdjustmentsButKeepsDirectionAndCurrentBinding() {
        val session = boundSession()
        session.direction = ChatDirection.SOOTHE
        session.add("旧调整")
        session.clear()
        assertTrue(session.adjustments.isEmpty())
        assertEquals(ChatDirection.SOOTHE, session.direction)
        session.add("清除后新调整")
        assertFalse(session.bind("contact-a|com.tencent.mm", snapshot().signature()))
        assertEquals(listOf("清除后新调整"), session.adjustments)
    }

    @Test
    fun resetClearsBindingAdjustmentsAndSelectedDirection() {
        val session = boundSession()
        session.direction = ChatDirection.CONNECT
        session.add("旧调整")
        session.reset()
        assertEquals(ChatDirection.DEFAULT, session.direction)
        assertTrue(session.adjustments.isEmpty())
        // Adding before bind makes clearing of the old binding observable.
        session.add("尚未绑定的新补充")
        assertTrue(session.bind("contact-a|com.tencent.mm", snapshot().signature()))
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun adjustmentsAreTrimmedAndInvalidInputDoesNotChangeExistingTurns() {
        val session = boundSession()
        session.add("  保留这条补充  \n")
        listOf("", " \t\n", "字".repeat(1001)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { session.add(invalid) }
        }
        assertEquals(listOf("保留这条补充"), session.adjustments)
        session.add("字".repeat(1000))
        assertEquals(1000, session.adjustments.last().length)
    }

    @Test
    fun twelveTurnsAreAllowedAndClearRestoresCapacity() {
        val session = boundSession()
        repeat(12) { session.add("第${it + 1}轮") }
        val before = session.adjustments
        assertThrows(IllegalArgumentException::class.java) { session.add("第十三轮") }
        assertEquals(before, session.adjustments)
        session.clear()
        session.add("重新开始")
        assertEquals(listOf("重新开始"), session.adjustments)
    }

    @Test
    fun returnedAdjustmentListCannotMutateSessionOrEarlierInput() {
        val session = boundSession()
        session.add("第一轮")
        session.add("第二轮")
        val copied = session.adjustments as MutableList<String>
        copied.clear()
        assertEquals(listOf("第一轮", "第二轮"), session.adjustments)
        val supplied = session.adjustments.toMutableList()
        val input = ConversationInput.build(snapshot(), null, "com.tencent.mm", "朋友", session.direction, supplied)
        supplied.add("调用方后续改动")
        session.add("第三轮")
        assertEquals(listOf("第一轮", "第二轮"), input.adjustments)
    }

    @Test
    fun adjustmentsNeverBecomeRealCurrentOrHistoricalMessages() {
        val session = boundSession()
        session.add("事实纠正：尚未确定周末安排")
        session.add("措辞偏好：不要用问号")
        val history = listOf(LogEntry("me", "上次说的是工作日", 0L, "com.tencent.mobileqq"))
        val context = ChatContext(null, history, emptyList())
        val input = ConversationInput.build(snapshot(), context, "com.tencent.mm", "朋友", session.direction, session.adjustments)
        val encoded = input.json(includeStyle = true)

        assertEquals(snapshot().messages, input.snapshot.messages)
        assertEquals(history, input.history)
        assertEquals(1, encoded.getJSONArray("current_messages").length())
        assertEquals(1, encoded.getJSONArray("history").length())
        assertEquals(2, encoded.getJSONArray("session_adjustments").length())
        assertEquals(listOf("事实纠正：尚未确定周末安排", "措辞偏好：不要用问号"), input.adjustments)
        assertTrue(input.visibleContext().none { msg -> session.adjustments.any { msg.text.contains(it) } })
    }

    @Test
    fun inputBuildUsesSelectedContactsContextAndDoesNotMutateIt() {
        val history = listOf(LogEntry("other", "已选联系人历史", 0L, "com.tencent.mobileqq"))
        val context = ChatContext(
            Contact(id = "selected", name = "小林", relationship = "同事", notes = "只讨论项目"),
            history, emptyList(), StyleProfile(source = "selected", summary = "简短直接")
        )
        val input = ConversationInput.build(snapshot(), context, "com.tencent.mm", "朋友", ChatDirection.CONTINUE, emptyList())
        assertEquals("同事", input.relationship)
        assertTrue(input.background.contains("只讨论项目"))
        assertEquals("简短直接", input.style)
        assertEquals(history, input.history)
        assertEquals(history, context.history)
        assertFalse(input.json(includeStyle = false).has("my_wording_style"))
        assertEquals("简短直接", input.json(includeStyle = true).getString("my_wording_style"))

        val withoutContext = ConversationInput.build(snapshot(), null, "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
        assertEquals("朋友", withoutContext.relationship)
        assertTrue(withoutContext.history.isEmpty())
        assertEquals("", withoutContext.background)
        assertEquals("", withoutContext.style)
        assertFalse(withoutContext.json(includeStyle = true).has("my_wording_style"))
    }

    @Test
    fun combinedMessageBudgetKeepsNewestHistoryAndCurrentMessages() {
        val current = snapshot().copy(messages = (1..98).map { Msg("other", "当前$it") })
        val history = (1..5).map { LogEntry("me", "历史$it", 0L, "com.tencent.mobileqq") }
        val input = ConversationInput.build(current, ChatContext(null, history, emptyList()), "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
        assertEquals(current.messages, input.snapshot.messages)
        assertEquals(listOf("历史4", "历史5"), input.history.map { it.text })
        assertEquals(100, input.visibleContext().size)
        assertEquals(5, history.size)
    }

    @Test
    fun currentMessagesHavePriorityWhenTheyExceedTheHundredMessageBudget() {
        val current = snapshot().copy(messages = (1..105).map { Msg("other", "当前$it") })
        val context = ChatContext(null, listOf(LogEntry("me", "旧历史", 0L, "com.tencent.mobileqq")), emptyList())
        val input = ConversationInput.build(current, context, "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
        assertEquals(100, input.snapshot.messages.size)
        assertEquals("当前6", input.snapshot.messages.first().text)
        assertEquals("当前105", input.snapshot.messages.last().text)
        assertTrue(input.history.isEmpty())
        assertEquals(105, current.messages.size)
    }

    private fun snapshot() = ChatSnapshot("测试联系人", listOf(Msg("other", "周末有空吗？")))

    private fun boundSession() = ConversationSession().apply {
        bind("contact-a|com.tencent.mm", snapshot().signature())
    }
}
