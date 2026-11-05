package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class ChatContinuityTest {
    private fun snapshot(vararg messages: String) = ChatSnapshot(null, messages.map { Msg("other", it) })

    @Test fun keyboardHidingOldRowsDoesNotChangeLatestTurn() {
        assertTrue(snapshot("一", "二", "三", "四").hasSameLatestMessages(snapshot("三", "四")))
    }

    @Test fun newMessageOrCorrectedSpeakerRequiresNewAnalysis() {
        val old = snapshot("问", "答")
        assertFalse(old.hasSameLatestMessages(snapshot("问", "答", "补充")))
        assertFalse(old.hasSameLatestMessages(ChatSnapshot(null, listOf(Msg("me", "答")))))
    }

    @Test fun emptyOrScrolledPageCannotBeTreatedAsSameLatestTurn() {
        assertFalse(snapshot("答").hasSameLatestMessages(snapshot()))
        assertFalse(snapshot("答").hasSameLatestMessages(snapshot("答").copy(atLatest = false)))
    }
}
