package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ChatWindowSelectionTest {
    private val wx = "com.tencent.mm"
    private val xhs = "com.xingin.xhs"
    private val own = "com.jev.probe.custom"
    private fun root(id: Int, pkg: String = wx, children: Int = 0) =
        ChatWindowSelection.Candidate(id, pkg, children)
    private fun select(vararg roots: ChatWindowSelection.Candidate, expected: String? = null) =
        ChatWindowSelection.select(roots.toList(), own, expected)

    @Test fun populatedSnapshotReplacesEmptyRootOfSameWindow() {
        assertEquals(1, select(root(5), root(5, children = 40)))
    }
    @Test fun populatedForegroundRemainsFirstChoice() {
        assertEquals(0, select(root(5, children = 40), root(5, children = 2)))
    }
    @Test fun backgroundWindowOfSameAppDoesNotSupplyOldChat() {
        assertEquals(0, select(root(5), root(6, children = 40)))
    }
    @Test fun expectedAppCannotSelectBackgroundWindow() {
        assertNull(select(root(1, xhs, 10), root(2, wx, 40), expected = wx))
    }
    @Test fun settingsInForegroundDoNotExposeChatBehindThem() {
        assertNull(select(root(1, own, 10), root(2, wx, 40)))
    }
    @Test fun differentPackageWithSameIdCannotSupplyMessages() {
        assertEquals(0, select(root(5), root(5, xhs, 40)))
    }
    @Test fun unknownWindowIdsAreNotTreatedAsSameWindow() {
        assertEquals(0, select(root(-1), root(-1, children = 40)))
    }
    @Test fun emptyProviderTreeIsNotInventedIntoAChat() {
        assertEquals(0, select(root(5), root(5)))
        assertNull(select())
    }
}
