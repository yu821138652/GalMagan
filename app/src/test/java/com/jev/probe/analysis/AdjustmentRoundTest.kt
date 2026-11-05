package com.jev.probe.analysis

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.RankedReply
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdjustmentRoundTest {
    private fun situation() = GeneralModelPipeline.parseSituation(AnalysisIntegrationTest.situation())
    private fun input() = ConversationInput.build(ChatSnapshot(null, listOf(Msg("other", "周末有空吗？"))),
        null, "com.tencent.mm", "朋友", ChatDirection.CONNECT, emptyList())

    @Test fun referencesToOldCandidatesKeepTheirRoundPairing() {
        val first = AdjustmentRound("第二条短一点", situation(), listOf(RankedReply("上一轮第二条")))
        val second = AdjustmentRound("别道歉", situation(), listOf(RankedReply("修正后第二条")))
        val input = input().copy(adjustments = listOf(first.userAdjustment, second.userAdjustment),
            adjustmentRounds = listOf(first, second))
        val encoded = input.json(true).getJSONArray("adjustment_rounds")
        assertEquals("上一轮第二条", encoded.getJSONObject(0).getJSONArray("prior_candidates").getJSONObject(0).getString("text"))
        assertEquals("修正后第二条", encoded.getJSONObject(1).getJSONArray("prior_candidates").getJSONObject(0).getString("text"))
        assertEquals("第二条短一点", encoded.getJSONObject(0).getString("user_adjustment"))
        assertEquals(1, input.json(true).getJSONArray("current_messages").length())
    }

    @Test fun clearingRemovesOldCorrectionsAndModelOutputsButKeepsRealContext() {
        val source = input().copy(adjustments = listOf("她在开玩笑"), previousAnalysis = situation(),
            previousReplies = listOf(RankedReply("旧候选")),
            adjustmentRounds = listOf(AdjustmentRound("她在开玩笑", situation(), emptyList())))
        val clean = source.withoutAdjustments()
        val encoded = clean.json(true)
        assertEquals(0, encoded.getJSONArray("session_adjustments").length())
        assertFalse(encoded.has("previous_assistant_output"))
        assertFalse(encoded.has("adjustment_rounds"))
        assertEquals(source.snapshot, clean.snapshot)
        assertEquals(ChatDirection.CONNECT, clean.direction)
    }

    @Test fun allRoundsAndPriorOutputReachTheThreeStagesAsSeparateData() {
        val round = AdjustmentRound("不是生气，短一点", situation(), listOf(RankedReply("旧候选")))
        val source = input().copy(adjustments = listOf(round.userAdjustment), adjustmentRounds = listOf(round),
            previousAnalysis = situation(), previousReplies = round.priorReplies)
        var requests = 0
        GeneralModelPipeline(ChatTransport { _, user, _ ->
            val body = JSONObject(user)
            assertEquals("不是生气，短一点", body.getJSONArray("adjustment_rounds").getJSONObject(0).getString("user_adjustment"))
            assertEquals("周末有空吗？", body.getJSONArray("current_messages").getJSONObject(0).getString("text"))
            requests++
            when (body.getString("stage")) {
                "analysis" -> AnalysisIntegrationTest.situation().toString()
                "draft" -> AnalysisIntegrationTest.candidates(false).toString()
                else -> AnalysisIntegrationTest.candidates(true).toString()
            }
        }).run(source)
        assertEquals(3, requests)
    }
}
