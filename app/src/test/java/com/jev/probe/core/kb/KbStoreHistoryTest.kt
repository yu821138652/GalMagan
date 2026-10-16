package com.jev.probe.core.kb

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KbStoreHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store(): KbStore {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp.root
        }
        val constructor = KbStore::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context)
    }

    private fun message(text: String, app: String = "com.tencent.mm", side: String = "me") =
        LogEntry(side = side, text = text, ts = 1234L, app = app)

    @Test fun editsOnlySelectedOccurrenceAndPreservesMetadata() {
        val store = store()
        val first = message("好")
        val second = message("好")
        val otherApp = message("好", "com.xingin.xhs")
        assertTrue(store.appendLog("contact", listOf(first, second, otherApp), screenBatch = false))
        assertTrue(store.updateLogEntry("contact", second.id, "other", "改正后的正文"))
        assertEquals(first, store.allLog("contact").single { it.id == first.id })
        assertEquals(second.copy(side = "other", text = "改正后的正文"),
            store.allLog("contact").single { it.id == second.id })
        assertEquals(otherApp, store.allLog("contact").single { it.id == otherApp.id })
        assertEquals(store.allLog("contact"), store().allLog("contact"))
    }

    @Test fun deletionTargetsOneDuplicateAndSurvivesReload() {
        val store = store()
        val first = message("好")
        val second = message("好")
        store.appendLog("contact", listOf(first, second), screenBatch = false)
        assertTrue(store.deleteLogEntry("contact", first.id))
        assertEquals(listOf(second), store().allLog("contact"))
        assertFalse(store.deleteLogEntry("contact", first.id))
    }

    @Test fun repeatedScreenAndNewTailDoNotUndoManualEdit() {
        val store = store()
        val screen = listOf(message("第一条"), message("识别错误"))
        store.appendLog("contact", screen)
        store.updateLogEntry("contact", screen.last().id, "other", "修正正文")
        store.appendLog("contact", screen)
        store.appendLog("contact", screen + message("新消息"))
        assertEquals(listOf("第一条", "修正正文", "新消息"), store.allLog("contact").map { it.text })
        assertEquals("other", store.allLog("contact")[1].side)
    }

    @Test fun deletingLastMessageDoesNotRestoreItOnNextCapture() {
        val store = store()
        val screen = listOf(message("保留"), message("删除"))
        store.appendLog("contact", screen)
        store.deleteLogEntry("contact", screen.last().id)
        store.appendLog("contact", screen + message("新消息"))
        assertEquals(listOf("保留", "新消息"), store.allLog("contact").map { it.text })
    }

    @Test fun deletingWholeScreenStillAllowsNewMessagesWithoutRestoringDeletedOnes() {
        val store = store()
        val screen = listOf(message("删除"))
        store.appendLog("contact", screen)
        store.deleteLogEntry("contact", screen.single().id)
        store().appendLog("contact", screen + message("新消息"))
        assertEquals(listOf("新消息"), store().allLog("contact").map { it.text })
    }

    @Test fun oldRecordsWithoutIdsCanBeEditedAndPersistTheirAssignedIds() {
        val file = File(temp.root, "kb/logs/contact.json")
        file.parentFile!!.mkdirs()
        file.writeText("""[{"side":"me","text":"old","ts":0,"app":"com.tencent.mm"}]""")
        val store = store()
        val entry = store.allLog("contact").single()
        assertTrue(entry.id.isNotBlank())
        assertTrue(store.updateLogEntry("contact", entry.id, "other", "corrected"))
        assertEquals(entry.copy(side = "other", text = "corrected"), store().allLog("contact").single())
    }

    @Test fun historyContextStillIncludesBothChatEnvironments() {
        val store = store()
        store.appendLog("contact", listOf(message("微信"), message("小红书", "com.xingin.xhs")))
        assertEquals(setOf("com.tencent.mm", "com.xingin.xhs"),
            store.recentLog("contact", 100).map { it.app }.toSet())
    }

    @Test fun batchDeleteRemovesOnlySelectedIdsAndPreservesOrderAndOtherContacts() {
        val store = store()
        val messages = listOf(message("重复"), message("重复"), message("保留"), message("小红书", "com.xingin.xhs"))
        store.appendLog("contact", messages, screenBatch = false)
        val other = message("另一联系人")
        store.appendLog("other", listOf(other))
        assertTrue(store.deleteLogEntries("contact", setOf(messages[0].id, messages[3].id)))
        assertEquals(messages.subList(1, 3), store().allLog("contact"))
        assertEquals(listOf(other), store().allLog("other"))
        assertFalse(store.deleteLogEntries("contact", emptySet()))
    }

    @Test fun batchDeletionIsNotUndoneByNextAutomaticScreenCapture() {
        val store = store()
        val screen = listOf(message("删一"), message("保留"), message("删二"))
        store.appendLog("contact", screen)
        store.deleteLogEntries("contact", setOf(screen[0].id, screen[2].id))
        val reloaded = store()
        reloaded.appendLog("contact", screen + message("新消息"))
        assertEquals(listOf("保留", "新消息"), store().allLog("contact").map { it.text })
    }
}
