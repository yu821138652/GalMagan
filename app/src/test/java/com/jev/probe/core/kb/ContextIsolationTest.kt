package com.jev.probe.core.kb

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.testing.TestContext
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextIsolationTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: TestContext
    private lateinit var prefs: Prefs
    private lateinit var store: KbStore
    private val wx = "com.tencent.mm"
    private val xhs = "com.xingin.xhs"

    private fun resetSingleton() {
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Before fun setup() {
        resetSingleton()
        context = TestContext(temp.root)
        prefs = Prefs(context, "context-test")
        store = KbStore.get(context)
        store.saveContact(Contact("a", "同名", apps = listOf(wx), notes = "甲的私人背景"))
        store.saveContact(Contact("b", "同名", apps = listOf(xhs), notes = "乙的私人背景"))
        store.appendLog("a", listOf(LogEntry("other", "甲的私人聊天", 0, wx)))
        store.appendLog("b", listOf(LogEntry("other", "乙在微信的聊天", 0, wx), LogEntry("me", "乙在小红书的聊天", 0, xhs)))
        store.saveStyleProfile(null, StyleProfile("全局", "全局表达"))
        store.saveStyleProfile("a", StyleProfile("面向甲", "面向甲的表达"))
    }
    @After fun teardown() { resetSingleton() }

    @Test fun explicitContactIdWinsOverSameNameAndAppHint() {
        val snapshot = ChatSnapshot("同名", listOf(Msg("other", "当前消息")))
        val ctx = ContextBuilder.build(context, snapshot, wx, prefs, selectedContactId = "b")
        assertEquals("b", ctx.contact!!.id)
        assertEquals(setOf(wx, xhs), ctx.history.map { it.app }.toSet())
        assertFalse(ctx.background("").contains("甲的私人背景"))
    }

    @Test fun defaultModeDoesNotUseAnyMatchedContactHistoryOrStyle() {
        val ctx = ContextBuilder.build(context, ChatSnapshot("同名", listOf(Msg("other", "当前消息"))), wx, prefs,
            useContactHistory = false, selectedContactId = "a")
        assertNull(ctx.contact)
        assertTrue(ctx.history.isEmpty())
        assertEquals("全局表达", ctx.style!!.summary)
        assertFalse(ctx.background("").contains("私人"))
    }

    @Test fun removedSelectedContactDoesNotFallBackToAnotherSameName() {
        val ctx = ContextBuilder.build(context, ChatSnapshot("同名", listOf(Msg("other", "当前消息"))), wx, prefs,
            selectedContactId = "deleted")
        assertNull(ctx.contact)
        assertTrue(ctx.history.isEmpty())
    }

    @Test fun matchingCurrentTextInAnotherEnvironmentIsStillIncluded() {
        val text = "两个软件都发过的同一句话"
        store.appendLog("b", listOf(LogEntry("me", text, 0, wx), LogEntry("me", text, 0, xhs)), screenBatch = false)
        val ctx = ContextBuilder.build(context, ChatSnapshot("同名", listOf(Msg("me", text))), wx, prefs, selectedContactId = "b")
        assertEquals(listOf(xhs), ctx.history.filter { it.text == text }.map { it.app })
    }

    @Test fun automaticRecordingTargetsSelectedIdAndKeepsOtherContactsIntact() {
        val snapshot = ChatSnapshot("同名", listOf(Msg("other", "乙在微信的聊天"), Msg("me", "只记在乙的记录里")))
        ContextBuilder.recordVisible(context, snapshot, wx, "b")
        assertEquals(listOf("甲的私人聊天"), store.allLog("a").map { it.text })
        assertTrue(store.allLog("b").any { it.text == "只记在乙的记录里" })
    }

    @Test fun currentShortMessagesAreInjectedOnceButOlderIdenticalMessagesRemain() {
        val entries = listOf("好", "之前的话题已经结束", "好", "新话题").map { LogEntry("me", it, 0, wx) }
        store.appendLog("b", entries, screenBatch = false)
        val snapshot = ChatSnapshot("同名", listOf(Msg("me", "好"), Msg("me", "新话题")))
        val ctx = ContextBuilder.build(context, snapshot, wx, prefs, selectedContactId = "b")
        assertEquals(listOf(entries[0].id), ctx.history.filter { it.text == "好" }.map { it.id })
        assertFalse(ctx.history.any { it.id == entries.last().id })
        assertTrue(ctx.history.any { it.app == xhs })
    }

    @Test fun automaticRecordingDoesNotAppendAVisibleOldPage() {
        val before = store.allLog("b")
        ContextBuilder.recordVisible(context, ChatSnapshot("同名", listOf(Msg("me", "旧页面")), atLatest = false), wx, "b")
        assertEquals(before, store.allLog("b"))
    }

    @Test fun largerScreenDoesNotDuplicateSavedTailWhileAutomaticWriteIsPending() {
        val entries = listOf("较早", "一", "二", "三").map { LogEntry("me", it, 0, wx) }
        store.appendLog("b", entries, screenBatch = false)
        val snapshot = ChatSnapshot("同名", listOf("零", "一", "二", "三", "四").map { Msg("me", it) })
        val ctx = ContextBuilder.build(context, snapshot, wx, prefs, selectedContactId = "b")
        assertFalse(ctx.history.any { it.id in entries.takeLast(3).map { entry -> entry.id } })
        assertTrue(ctx.history.any { it.id == entries.first().id })
    }
}
