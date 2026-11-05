package com.jev.probe.analysis

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.testing.TestContext
import com.jev.probe.jev.RequestCancellation
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun prefs() = Prefs(TestContext(temp.root), "integration")

    @Test fun onlyGeneralRouteConfigurationIsRequired() {
        val prefs = prefs()
        assertEquals(Prefs.MODE_GENERAL, prefs.analysisMode)
        assertFalse(prefs.hasKey())
        prefs.judgeKey = "fixture-only-judge"
        assertEquals("", prefs.effectiveReplyKey())
        assertFalse(prefs.hasKey())
        prefs.replyKey = "fixture-only-general"
        assertTrue(prefs.hasKey())
        assertEquals("gpt-6-luna", prefs.replyModel)
        prefs.judgeKey = ""
        assertTrue(prefs.hasKey())
        prefs.replyBaseUrl = "not-a-url"
        assertFalse(prefs.hasKey())
    }

    @Test fun existingChatConfigurationIsPreservedAndEndpointIsNotDuplicated() {
        val prefs = prefs()
        prefs.replyBaseUrl = "https://relay.example/v1/chat/completions/"
        prefs.replyModel = "configured-model"
        assertEquals("https://relay.example/v1/chat/completions", prefs.replyEndpoint())
        assertEquals("configured-model", prefs.replyModel)
    }

    @Test fun generalModeMakesThreeChatRequestsWithoutAnyJevKey() = exerciseRoute(false)
    @Test fun generalModeDoesNotCallEvenAConfiguredJevRoute() = exerciseRoute(false, configureJev = true)
    @Test fun exhaustedOptionalJevDoesNotPreventTheWholeFlow() = exerciseRoute(true)

    @Test fun stoppingAnInFlightRequestReleasesWorkerAndCannotContinueToDraft() {
        val server = MockWebServer()
        val executor = Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val cancellation = RequestCancellation()
        try {
            val prefs = prefs().apply {
                replyBaseUrl = server.url("/v1").toString()
                replyKey = "fixture-only-general"
            }
            val input = ConversationInput.build(ChatSnapshot(null, listOf(Msg("other", "你好"))),
                null, "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
            val future = executor.submit<com.jev.probe.core.Analysis> {
                GeneralAnalysisClient(prefs, cancellation).analyze(input, { !cancellation.cancelled }, {}, {})
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            cancellation.cancel()
            try {
                future.get(3, TimeUnit.SECONDS)
                fail("停止后不应得到分析结果")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is CancellationException)
            }
            assertEquals(1, server.requestCount)
            // The same worker can immediately perform a separate analysis.
            listOf(situation(), candidates(false), candidates(true)).forEach { content ->
                server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", content.toString())))).toString()))
            }
            val next = executor.submit<com.jev.probe.core.Analysis> {
                GeneralAnalysisClient(prefs).analyze(input, { true }, {}, {})
            }.get(5, TimeUnit.SECONDS)
            assertEquals(3, next.rankedReplies.size)
            assertEquals(4, server.requestCount)
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun cancelledBeforeConnectingMakesNoModelRequest() {
        val server = MockWebServer()
        server.start()
        try {
            val prefs = prefs().apply {
                replyBaseUrl = server.url("/v1").toString()
                replyKey = "fixture-only-general"
            }
            val cancellation = RequestCancellation().apply { cancel() }
            val input = ConversationInput.build(ChatSnapshot(null, listOf(Msg("other", "你好"))),
                null, "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
            try {
                GeneralAnalysisClient(prefs, cancellation).analyze(input, { true }, {}, {})
                fail("已取消的请求不应发出")
            } catch (_: CancellationException) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun exerciseRoute(optionalJev: Boolean, configureJev: Boolean = optionalJev) {
        val server = MockWebServer()
        val stages = mutableListOf<String>()
        var jevCalls = 0
        server.dispatcher = object : Dispatcher() {
          override fun dispatch(httpRequest: RecordedRequest): MockResponse {
            val response: String
            val status: Int
            if (httpRequest.path == "/jev") {
                jevCalls++
                status = 402
                response = "{\"error\":\"额度不足\"}"
            } else {
                val request = JSONObject(httpRequest.body.readUtf8())
                assertEquals("/v1/chat/completions", httpRequest.path)
                assertEquals("gpt-6-luna", request.getString("model"))
                assertFalse(request.has("questions"))
                val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                val stage = input.getString("stage")
                stages.add(stage)
                val content = when (stage) {
                    "analysis" -> situation()
                    "draft" -> { assertEquals("朋友聚餐", input.getJSONObject("analysis").getString("topic")); candidates(false) }
                    else -> { assertEquals(3, input.getJSONArray("draft_candidates").length()); candidates(true) }
                }
                response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", content.toString())))).toString()
                status = 200
            }
            return MockResponse().setResponseCode(status).setBody(response)
          }
        }
        server.start()
        try {
            val prefs = prefs().apply {
                replyBaseUrl = server.url("/v1").toString()
                replyKey = "fixture-only-general"
                judgeProvider = Prefs.PROVIDER_CUSTOM
                judgeBaseUrl = server.url("/jev").toString()
                judgeKey = if (configureJev) "fixture-only-judge" else ""
                analysisMode = if (optionalJev) Prefs.MODE_JEV_ASSISTED else Prefs.MODE_GENERAL
            }
            val snapshot = ChatSnapshot(null, listOf(Msg("other", "今晚吃火锅吗？")))
            val input = ConversationInput.build(snapshot, null, "com.tencent.mm", "朋友", ChatDirection.DEFAULT, emptyList())
            val result = GeneralAnalysisClient(prefs).analyze(input, { true }, {}, {})
            assertEquals(listOf("analysis", "draft", "review"), stages)
            assertEquals(if (optionalJev) 1 else 0, jevCalls)
            assertEquals(3, result.rankedReplies.size)
            assertEquals(0, result.situation!!.riskLevel)
            if (optionalJev) assertTrue(result.reviewSummary.contains("独立完成"))
        } finally { server.shutdown() }
    }

    companion object {
        fun situation(): JSONObject = JSONObject("""{
            "topic":"朋友聚餐","unresolved":["地点尚未确定"],"facts":["对方邀请吃火锅"],
            "interpretations":["可能希望确认时间地点"],"missing_context":[],"risk_level":0,
            "risk_reason":"未见抱怨或冲突","strategy":{"goal":"自然确认安排","actions":["确认地点"],"cautions":["不要编造安排"]},
            "corrected_facts":[],"expression_preferences":[]
        }""")

        fun candidates(reviewed: Boolean): JSONObject {
            val array = JSONArray()
            listOf("好呀，你想去哪家？", "可以，几点碰面？", "想吃清汤还是辣锅？").forEachIndexed { i, text ->
                val candidate = JSONObject().put("text", text).put("approach", "侧重${i + 1}")
                if (reviewed) candidate.put("checks", JSONObject().apply {
                    listOf("speaker", "self_answer", "facts_promises", "latest", "direction", "boundaries_conflict").forEach { put(it, true) }
                })
                array.put(candidate)
            }
            return JSONObject().put("candidates", array).apply { if (reviewed) put("review_summary", "保留自然承接，未增加未确认的安排") }
        }
    }
}
