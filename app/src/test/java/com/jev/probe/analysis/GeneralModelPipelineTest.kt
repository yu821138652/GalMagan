package com.jev.probe.analysis

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.kb.ChatEnvironment
import com.jev.probe.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** Offline protocol tests with scripted responses; these do not measure model accuracy. */
class GeneralModelPipelineTest {
    private val checkKeys = listOf(
        "speaker", "self_answer", "facts_promises", "latest", "direction", "boundaries_conflict"
    )

    @Test
    fun threeRequestsDependOnThisRoundsAnalysisAndDrafts() {
        val analysis = situation("本轮事实标记", 4)
        val drafts = candidates("草稿")
        val reviewed = review("已修正候选")
        val partials = mutableListOf<Analysis>()
        val progress = mutableListOf<String>()
        val transport = FakeTransport { request, index ->
            when (index) {
                0 -> {
                    assertEquals("analysis", request.stage)
                    assertFalse(request.body.has("analysis"))
                    assertTrue(partials.isEmpty())
                    analysis.toString()
                }
                1 -> {
                    assertEquals("draft", request.stage)
                    assertJson(analysis, request.body.getJSONObject("analysis"))
                    assertEquals("本轮事实标记", partials.single().situation!!.topic)
                    assertTrue(partials.single().rankedReplies.isEmpty())
                    drafts.toString()
                }
                2 -> {
                    assertEquals("review", request.stage)
                    assertJson(analysis, request.body.getJSONObject("analysis"))
                    assertJson(drafts.getJSONArray("candidates"), request.body.getJSONArray("draft_candidates"))
                    reviewed.toString()
                }
                else -> error("Unexpected extra request")
            }
        }

        val result = GeneralModelPipeline(transport).run(
            input(), onProgress = { progress.add(it) }, onSituation = { partials.add(it) }
        )

        assertEquals(listOf("analysis", "draft", "review"), transport.stages())
        assertEquals(listOf(0.2, 0.7, 0.2), transport.requests.map { it.temperature })
        assertEquals(3, progress.size)
        assertEquals(texts(reviewed), result.rankedReplies.map { it.text })
        assertEquals(4.0, result.dangerLevel!!.score, 0.0)
        assertEquals("假模型评审记录", result.reviewSummary)
    }

    @Test
    fun consecutiveRunsDoNotReusePreviousAnalysisOrCandidate() {
        val transport = FakeTransport { request, index ->
            val round = index / 3
            val expected = situation("round-$round", round + 2)
            if (request.stage != "analysis") assertJson(expected, request.body.getJSONObject("analysis"))
            if (request.stage == "review") {
                assertJson(candidates("draft-$round").getJSONArray("candidates"), request.body.getJSONArray("draft_candidates"))
            }
            response(request, expected, candidates("draft-$round"), review("final-$round"))
        }
        val pipeline = GeneralModelPipeline(transport)
        val first = pipeline.run(input())
        val second = pipeline.run(input().copy(snapshot = snapshot(Msg("other", "这是刚收到的新消息"))))

        assertEquals("round-0", first.situation!!.topic)
        assertEquals("round-1", second.situation!!.topic)
        assertEquals(texts(review("final-1")), second.rankedReplies.map { it.text })
        assertEquals("这是刚收到的新消息", transport.requests[3].body.getJSONArray("current_messages").getJSONObject(0).getString("text"))
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun myLastMessageStillRunsRiskAnalysisAndAllDependentStages() {
        val source = input().copy(snapshot = snapshot(
            Msg("other", "我已经说过别再催我了"), Msg("me", "刚才又催了一次，是我着急了")
        ))
        val partials = mutableListOf<Analysis>()
        val transport = FakeTransport { request, _ -> response(request, situation("脚本风险", 7)) }

        val result = GeneralModelPipeline(transport).run(source, onSituation = { partials.add(it) })

        assertEquals("me", source.snapshot.latestFrom)
        assertEquals(listOf("analysis", "draft", "review"), transport.stages())
        assertEquals(7.0, result.dangerLevel!!.score, 0.0)
        assertEquals(7, partials.single().situation!!.riskLevel)
        assertTrue(transport.requests.first().system.contains("我方最后发送也要分析整段情境"))
        transport.requests.forEach { assertCurrent(source, it.body) }
    }

    @Test
    fun wordingStyleOnlyEntersDraftAndReview() {
        val source = input().copy(style = "专属风格标记：简短、少用感叹号")
        val transport = FakeTransport { request, _ -> response(request) }
        GeneralModelPipeline(transport).run(source)

        assertFalse(transport.requests[0].body.has("my_wording_style"))
        assertFalse(transport.requests[0].body.toString().contains(source.style))
        transport.requests.drop(1).forEach {
            assertEquals(source.style, it.body.getString("my_wording_style"))
        }
    }

    @Test
    fun allAdjustmentTurnsAndDirectionReachEveryStageWithoutChangingMessages() {
        val source = input().copy(
            direction = ChatDirection.CONNECT,
            adjustments = listOf("不是约会，是同事间讨论", "不要替我承诺加班", "上一条更正：是同学，不是同事")
        )
        val transport = FakeTransport { request, _ -> response(request) }
        GeneralModelPipeline(transport).run(source)

        transport.requests.forEach {
            assertCurrent(source, it.body)
            assertJson(JSONArray(source.adjustments), it.body.getJSONArray("session_adjustments"))
            assertEquals(source.direction.label, it.body.getJSONObject("direction").getString("name"))
            assertEquals(source.direction.guidance, it.body.getJSONObject("direction").getString("guidance"))
        }
    }

    @Test
    fun crossEnvironmentHistoryPreservesSourcesAndOnlyOriginalKnownTimes() {
        val source = input().copy(history = listOf(
            LogEntry("other", "微信历史", 0L, "com.tencent.mm"),
            LogEntry("me", "QQ历史", 1720000000123L, "com.tencent.mobileqq"),
            LogEntry("other", "飞书历史", -1L, "com.ss.android.lark")
        ))
        val transport = FakeTransport { request, _ -> response(request) }
        GeneralModelPipeline(transport).run(source)

        transport.requests.forEach { request ->
            val history = request.body.getJSONArray("history")
            assertEquals(3, history.length())
            source.history.forEachIndexed { index, entry ->
                val actual = history.getJSONObject(index)
                assertEquals(entry.side, actual.getString("from"))
                assertEquals(entry.text, actual.getString("text"))
                assertEquals(ChatEnvironment.label(entry.app), actual.getString("environment"))
                val expectedKeys = mutableSetOf("from", "text", "environment")
                if (entry.ts > 0) {
                    expectedKeys.add("original_time_ms")
                    assertEquals(entry.ts, actual.getLong("original_time_ms"))
                }
                assertEquals(expectedKeys, actual.keySet())
            }
            assertCurrent(source, request.body)
        }
    }

    @Test
    fun optionalRiskReferenceDoesNotOverrideScriptedAnalysis() {
        val reference = "Jev风险参考：9/9；仅供参考"
        val transport = FakeTransport { request, _ -> response(request, situation("独立分析", 1)) }
        val result = GeneralModelPipeline(transport).run(input(), auxiliaryRisk = reference)

        assertEquals(reference, transport.requests[0].body.getString("optional_jev_reference"))
        transport.requests.drop(1).forEach { assertFalse(it.body.has("optional_jev_reference")) }
        assertEquals(1.0, result.dangerLevel!!.score, 0.0)
        assertTrue(transport.requests.first().system.contains("不能取代聊天事实"))
    }

    @Test
    fun successfulOutputDoesNotFabricateProbabilitiesOrPsychologicalChoices() {
        val reviewed = review()
        repeat(3) { reviewed.getJSONArray("candidates").getJSONObject(it).put("prob", 0.99).put("confidence", 0.98) }
        val transport = FakeTransport { request, _ -> response(request, reviewed = reviewed) }
        val result = GeneralModelPipeline(transport).run(input())

        assertTrue(result.rankedReplies.all { it.prob == 0.0 })
        assertEquals(0.0, result.dangerLevel!!.confidence, 0.0)
        assertEquals(9, result.dangerLevel!!.maxLevel)
        assertNull(result.trueIntent)
        assertNull(result.sheNeeds)
        assertNull(result.shouldReplyNow)
        assertNull(result.bestAction)
        assertNull(result.tensionResolved)
        assertNull(result.literalQuestion)
        transport.requests.forEach { assertTrue(it.system.contains("不输出置信度、概率、百分比")) }
    }

    @Test
    fun reviewedOrderAndApproachesReplaceDraftOutput() {
        val reviewed = review()
        val array = reviewed.getJSONArray("candidates")
        reviewed.put("candidates", JSONArray().put(array.getJSONObject(2)).put(array.getJSONObject(0)).put(array.getJSONObject(1)))
        val transport = FakeTransport { request, _ -> response(request, reviewed = reviewed) }
        val result = GeneralModelPipeline(transport).run(input())

        assertEquals(texts(reviewed), result.rankedReplies.map { it.text })
        assertEquals(listOf("侧重3", "侧重1", "侧重2"), result.rankedReplies.map { it.approach })
        assertFalse(result.rankedReplies.any { it.text.startsWith("草稿") })
    }

    @Test
    fun malformedAnalysisGetsOneRepairWithOriginalContextAndPreviousResponse() {
        val malformed = "{这不是合法JSON"
        val transport = FakeTransport { request, index ->
            if (index == 0) malformed else response(request, situation("修复后本轮事实", 5))
        }
        val result = GeneralModelPipeline(transport).run(input())

        assertEquals(listOf("analysis", "analysis", "draft", "review"), transport.stages())
        assertRepair(transport.requests[0], transport.requests[1], malformed)
        assertEquals("修复后本轮事实", transport.requests[2].body.getJSONObject("analysis").getString("topic"))
        assertEquals(5, result.situation!!.riskLevel)
    }

    @Test
    fun invalidDraftGetsOneRepairAndReviewSeesOnlyRepairedDrafts() {
        val invalid = candidates().put("candidates", JSONArray()).toString()
        val repaired = candidates("修复草稿")
        val transport = FakeTransport { request, index ->
            if (index == 1) invalid else response(request, draft = repaired)
        }
        GeneralModelPipeline(transport).run(input())

        assertEquals(listOf("analysis", "draft", "draft", "review"), transport.stages())
        assertRepair(transport.requests[1], transport.requests[2], invalid)
        assertJson(repaired.getJSONArray("candidates"), transport.requests[3].body.getJSONArray("draft_candidates"))
    }

    @Test
    fun failedReviewGetsOneRepairAndOnlyCorrectedCandidatesAreReturned() {
        val invalid = review("未通过")
        invalid.getJSONArray("candidates").getJSONObject(1).getJSONObject("checks").put("latest", false)
        val repaired = review("修正后")
        val transport = FakeTransport { request, index ->
            if (index == 2) invalid.toString() else response(request, reviewed = repaired)
        }
        val result = GeneralModelPipeline(transport).run(input())

        assertEquals(listOf("analysis", "draft", "review", "review"), transport.stages())
        assertRepair(transport.requests[2], transport.requests[3], invalid.toString())
        assertEquals(texts(repaired), result.rankedReplies.map { it.text })
    }

    @Test
    fun everyCheckOnEveryCandidateMustBePresentAndBooleanTrue() {
        // 6 checks x 3 positions x 4 invalid forms = 72 rejection scenarios.
        checkKeys.forEach { key ->
            repeat(3) { position ->
                listOf("false", "missing", "string", "number").forEach { invalidForm ->
                    val bad = review()
                    val checks = bad.getJSONArray("candidates").getJSONObject(position).getJSONObject("checks")
                    when (invalidForm) {
                        "false" -> checks.put(key, false)
                        "missing" -> checks.remove(key)
                        "string" -> checks.put(key, "true")
                        "number" -> checks.put(key, 1)
                    }
                    val partials = mutableListOf<Analysis>()
                    val transport = FakeTransport { request, _ -> response(request, reviewed = bad) }
                    val failure = assertThrows("$key/$position/$invalidForm", IllegalArgumentException::class.java) {
                        GeneralModelPipeline(transport).run(input(), onSituation = { partials.add(it) })
                    }
                    assertTrue(failure.message.orEmpty().startsWith("review "))
                    assertEquals(listOf("analysis", "draft", "review", "review"), transport.stages())
                    assertTrue(partials.single().rankedReplies.isEmpty())
                }
            }
        }
    }

    @Test
    fun eachStageStopsAfterSecondInvalidResponseWithoutFallbackCandidates() {
        listOf("analysis", "draft", "review").forEachIndexed { index, stage ->
            val partials = mutableListOf<Analysis>()
            val transport = FakeTransport { request, _ ->
                if (request.stage == stage) "not-json" else response(request)
            }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                GeneralModelPipeline(transport).run(input(), onSituation = { partials.add(it) })
            }
            assertTrue(failure.message.orEmpty().startsWith("$stage "))
            assertEquals(listOf("analysis", "draft", "review").take(index) + listOf(stage, stage), transport.stages())
            assertEquals(if (stage == "analysis") 0 else 1, partials.size)
            assertTrue(partials.all { it.rankedReplies.isEmpty() })
        }
    }

    @Test
    fun transportFailureAtAnyStagePropagatesWithoutRetryOrFallback() {
        listOf("analysis", "draft", "review").forEachIndexed { index, stage ->
            val failure = IOException("offline transport failure: $stage")
            val partials = mutableListOf<Analysis>()
            val transport = FakeTransport { request, _ ->
                if (request.stage == stage) throw failure else response(request)
            }
            val thrown = assertThrows(IOException::class.java) {
                GeneralModelPipeline(transport).run(input(), onSituation = { partials.add(it) })
            }
            assertSame(failure, thrown)
            assertEquals(index + 1, transport.requests.size)
            assertTrue(partials.all { it.rankedReplies.isEmpty() })
        }
    }

    @Test
    fun cancelledRunDoesNotSendAnyRequest() {
        val transport = FakeTransport { _, _ -> error("Must not call transport") }
        assertThrows(CancellationException::class.java) {
            GeneralModelPipeline(transport).run(input(), isActive = { false })
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun cancellationAfterAnyResponseStopsBeforeConsumingItOrStartingNextStage() {
        listOf("analysis", "draft", "review").forEachIndexed { index, stage ->
            var active = true
            val partials = mutableListOf<Analysis>()
            val transport = FakeTransport { request, _ ->
                if (request.stage == stage) active = false
                response(request)
            }
            assertThrows(CancellationException::class.java) {
                GeneralModelPipeline(transport).run(input(), isActive = { active }, onSituation = { partials.add(it) })
            }
            assertEquals(index + 1, transport.requests.size)
            assertEquals(if (stage == "analysis") 0 else 1, partials.size)
            assertTrue(partials.all { it.rankedReplies.isEmpty() })
        }
    }

    @Test
    fun emptyCurrentMessagesFailBeforeAnyRequestEvenWithHistory() {
        val source = input().copy(
            snapshot = snapshot(), history = listOf(LogEntry("other", "只有旧记录", 0L, "com.tencent.mm"))
        )
        val transport = FakeTransport { _, _ -> error("Must not call transport") }
        assertThrows(IllegalArgumentException::class.java) { GeneralModelPipeline(transport).run(source) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun fencedJsonIsAcceptedAtEveryStageWithoutRepair() {
        val transport = FakeTransport { request, _ -> "```json\n${response(request)}\n```" }
        val result = GeneralModelPipeline(transport).run(input())
        assertEquals(listOf("analysis", "draft", "review"), transport.stages())
        assertEquals(3, result.rankedReplies.size)
    }

    @Test
    fun riskLevelRequiresAnIntegerFromZeroThroughNine() {
        listOf<Any>(-1, 10, 2.5, "3", true, JSONObject.NULL).forEach { invalid ->
            assertThrows("risk_level=$invalid", IllegalArgumentException::class.java) {
                GeneralModelPipeline.parseSituation(situation().put("risk_level", invalid))
            }
        }
        listOf(0, 9).forEach { boundary ->
            assertEquals(boundary, GeneralModelPipeline.parseSituation(situation(risk = boundary)).riskLevel)
        }
    }

    @Test
    fun situationKeepsFactsInterpretationsCorrectionsAndPreferencesSeparate() {
        val source = situation().put("facts", JSONArray(listOf("对方：还没决定")))
            .put("interpretations", JSONArray(listOf("可能需要考虑，不代表同意")))
            .put("corrected_facts", JSONArray(listOf("尚未约定日期")))
            .put("expression_preferences", JSONArray(listOf("不要用叠词")))
        val parsed = GeneralModelPipeline.parseSituation(source)
        assertEquals(listOf("对方：还没决定"), parsed.facts)
        assertEquals(listOf("可能需要考虑，不代表同意"), parsed.interpretations)
        assertEquals(listOf("尚未约定日期"), parsed.correctedFacts)
        assertEquals(listOf("不要用叠词"), parsed.expressionPreferences)
        assertJson(source, parsed.json())

        listOf("unresolved", "facts", "interpretations", "missing_context", "corrected_facts", "expression_preferences").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                GeneralModelPipeline.parseSituation(situation().put(key, JSONArray().put(42)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeneralModelPipeline.parseSituation(situation().put("topic", " "))
        }
        val badStrategy = situation()
        badStrategy.getJSONObject("strategy").put("goal", "")
        assertThrows(IllegalArgumentException::class.java) { GeneralModelPipeline.parseSituation(badStrategy) }
    }

    @Test
    fun invalidCandidateShapeNeverGetsPaddedOrExposedInDraftOrReview() {
        listOf("draft", "review").forEach { stage ->
            val invalidResponses = listOf(
                candidates().put("candidates", JSONArray()),
                candidates().apply { getJSONArray("candidates").remove(2) },
                candidates().apply { getJSONArray("candidates").put(JSONObject().put("text", "多余").put("approach", "多余")) },
                candidates().apply { getJSONArray("candidates").getJSONObject(2).put("text", "草稿1") },
                candidates().apply { getJSONArray("candidates").getJSONObject(1).put("text", " ") },
                candidates().apply { getJSONArray("candidates").getJSONObject(1).put("text", 123) },
                candidates().apply { getJSONArray("candidates").getJSONObject(1).remove("approach") }
            )
            invalidResponses.forEach { invalid ->
                // Supply valid review metadata so candidate shape is the failing contract.
                invalid.put("review_summary", "脚本评审")
                val array = invalid.getJSONArray("candidates")
                repeat(array.length()) { array.getJSONObject(it).put("checks", passingChecks()) }
                val partials = mutableListOf<Analysis>()
                val transport = FakeTransport { request, _ ->
                    if (request.stage == stage) invalid.toString() else response(request)
                }
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    GeneralModelPipeline(transport).run(input(), onSituation = { partials.add(it) })
                }
                assertTrue(failure.message.orEmpty().startsWith("$stage "))
                assertEquals(2, transport.requests.count { it.stage == stage })
                if (stage == "draft") assertFalse(transport.stages().contains("review"))
                assertTrue(partials.single().rankedReplies.isEmpty())
            }
        }
    }

    @Test
    fun reviewNeedsANonblankSummaryInAdditionToPassingChecks() {
        listOf("missing", "blank").forEach { variant ->
            val invalid = review()
            if (variant == "missing") invalid.remove("review_summary") else invalid.put("review_summary", " ")
            val transport = FakeTransport { request, _ -> response(request, reviewed = invalid) }
            assertThrows(IllegalArgumentException::class.java) { GeneralModelPipeline(transport).run(input()) }
            assertEquals(listOf("analysis", "draft", "review", "review"), transport.stages())
        }
    }

    @Test
    fun chineseCasesExerciseOfflineRequestContractsNotJudgmentAccuracy() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/analysis-cases-zh.json"))
        val suite = resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals("offline_contract_not_accuracy", suite.getString("testType"))
        val cases = suite.getJSONArray("cases")
        val categories = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            assertTrue("Duplicate case id: $id", ids.add(id))
            categories.add(case.getString("category"))
            assertTrue(id, case.getJSONArray("qualityExpectations").length() >= 3)
            val source = caseInput(case)
            val scriptedAnalysis = situation("fixture:$id", 3)
            val scriptedDrafts = candidates("fixture-draft:$id:")
            val scriptedReview = review("fixture-reviewed:$id:")
            val transport = FakeTransport { request, _ ->
                assertCurrent(source, request.body)
                assertEquals(case.getString("background"), request.body.getString("background"))
                assertEquals(case.getString("relationship"), request.body.getString("relationship"))
                assertJson(case.getJSONArray("adjustments"), request.body.getJSONArray("session_adjustments"))
                assertEquals(source.direction.label, request.body.getJSONObject("direction").getString("name"))
                assertEquals(source.direction.guidance, request.body.getJSONObject("direction").getString("guidance"))
                assertCaseHistory(case.getJSONArray("history"), request.body.getJSONArray("history"))
                if (request.stage == "analysis") {
                    assertFalse(request.body.has("my_wording_style"))
                } else {
                    assertEquals(source.style, request.body.getString("my_wording_style"))
                    assertJson(scriptedAnalysis, request.body.getJSONObject("analysis"))
                }
                if (request.stage == "review") {
                    assertJson(scriptedDrafts.getJSONArray("candidates"), request.body.getJSONArray("draft_candidates"))
                    checkKeys.forEach { assertTrue("$id: $it", request.system.contains(it)) }
                }
                response(request, scriptedAnalysis, scriptedDrafts, scriptedReview)
            }
            val result = GeneralModelPipeline(transport).run(source)
            assertEquals(id, listOf("analysis", "draft", "review"), transport.stages())
            // A scripted risk and scripted wording are transport sentinels, never semantic gold labels.
            assertEquals(id, texts(scriptedReview), result.rankedReplies.map { it.text })
            assertEquals(id, "fixture:$id", result.situation!!.topic)
        }
        assertTrue(categories.containsAll(listOf(
            "正常聊天", "冲突", "玩笑", "讽刺", "我方最后发送", "缺背景", "明确拒绝", "跨环境历史", "多轮补充"
        )))
    }

    @Test
    fun runningPipelineDoesNotMutateTheConversationInput() {
        val source = input().copy(
            history = listOf(LogEntry("me", "历史原文", 0L, "com.tencent.mobileqq")),
            adjustments = listOf("只调整措辞"), style = "独立风格"
        )
        val before = source.json(includeStyle = true)
        val transport = FakeTransport { request, _ -> response(request) }
        GeneralModelPipeline(transport).run(source)
        assertJson(before, source.json(includeStyle = true))
    }

    private fun input() = ConversationInput(
        snapshot(Msg("me", "刚忙完"), Msg("other", "今天怎么样？")),
        emptyList(), "com.tencent.mm", "朋友", "", "", ChatDirection.DEFAULT, emptyList()
    )

    private fun snapshot(vararg messages: Msg) = ChatSnapshot("测试联系人", messages.toList())

    private fun situation(topic: String = "脚本情境", risk: Int = 3) = JSONObject()
        .put("topic", topic).put("unresolved", JSONArray()).put("facts", JSONArray(listOf("对方发来消息")))
        .put("interpretations", JSONArray()).put("missing_context", JSONArray())
        .put("risk_level", risk).put("risk_reason", "脚本数据，不代表真实判断")
        .put("strategy", JSONObject().put("goal", "脚本策略").put("actions", JSONArray(listOf("承接话题")))
            .put("cautions", JSONArray(listOf("不要编造事实"))))
        .put("corrected_facts", JSONArray()).put("expression_preferences", JSONArray())

    private fun candidates(prefix: String = "草稿") = JSONObject().put("candidates", JSONArray().apply {
        repeat(3) { put(JSONObject().put("text", "$prefix${it + 1}").put("approach", "侧重${it + 1}")) }
    })

    private fun passingChecks() = JSONObject().apply { checkKeys.forEach { put(it, true) } }

    private fun review(prefix: String = "评审候选") = candidates(prefix).apply {
        repeat(3) { getJSONArray("candidates").getJSONObject(it).put("checks", passingChecks()) }
        put("review_summary", "假模型评审记录")
    }

    private fun texts(response: JSONObject): List<String> = response.getJSONArray("candidates").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("text") }
    }

    private fun response(
        request: Request, analysis: JSONObject = situation(),
        draft: JSONObject = candidates(), reviewed: JSONObject = review()
    ): String = when (request.stage) {
        "analysis" -> analysis.toString()
        "draft" -> draft.toString()
        "review" -> reviewed.toString()
        else -> error("Unknown stage: ${request.stage}")
    }

    private fun assertJson(expected: JSONObject, actual: JSONObject) {
        assertTrue("Expected $expected, got $actual", expected.similar(actual))
    }

    private fun assertJson(expected: JSONArray, actual: JSONArray) {
        assertTrue("Expected $expected, got $actual", expected.similar(actual))
    }

    private fun assertCurrent(source: ConversationInput, body: JSONObject) {
        val expected = JSONArray().apply {
            source.snapshot.messages.forEachIndexed { index, msg ->
                put(JSONObject().put("id", "current-${index + 1}").put("from", msg.side)
                    .put("text", msg.text).put("environment", ChatEnvironment.label(source.environment)))
            }
        }
        assertJson(expected, body.getJSONArray("current_messages"))
    }

    private fun assertRepair(original: Request, repaired: Request, previous: String) {
        assertEquals(original.system, repaired.system)
        assertEquals(original.temperature, repaired.temperature, 0.0)
        assertEquals(previous, repaired.body.getString("previous_response_to_fix"))
        assertTrue(repaired.body.getString("format_correction").contains("不能用假的通过标记"))
        val withoutRepair = JSONObject(repaired.body.toString())
        withoutRepair.remove("previous_response_to_fix")
        withoutRepair.remove("format_correction")
        assertJson(original.body, withoutRepair)
    }

    private fun caseInput(case: JSONObject): ConversationInput {
        val messages = case.getJSONArray("messages")
        val history = case.getJSONArray("history")
        val adjustments = case.getJSONArray("adjustments")
        return ConversationInput(
            snapshot(*(0 until messages.length()).map {
                messages.getJSONObject(it).let { msg -> Msg(msg.getString("from"), msg.getString("text")) }
            }.toTypedArray()),
            (0 until history.length()).map {
                history.getJSONObject(it).let { entry ->
                    LogEntry(entry.getString("from"), entry.getString("text"),
                        entry.optLong("original_time_ms", 0L), entry.getString("environment"))
                }
            },
            case.getString("environment"), case.getString("relationship"), case.getString("background"),
            case.getString("style"), ChatDirection.valueOf(case.getString("direction")),
            (0 until adjustments.length()).map { adjustments.getString(it) }
        )
    }

    private fun assertCaseHistory(expected: JSONArray, actual: JSONArray) {
        assertEquals(expected.length(), actual.length())
        repeat(expected.length()) { index ->
            val entry = expected.getJSONObject(index)
            val normalized = JSONObject(entry.toString())
                .put("environment", ChatEnvironment.label(entry.getString("environment")))
            assertJson(normalized, actual.getJSONObject(index))
        }
    }

    private data class Request(val system: String, val body: JSONObject, val temperature: Double) {
        val stage: String get() = body.getString("stage")
    }

    private class FakeTransport(private val respond: (Request, Int) -> String) : ChatTransport {
        val requests = mutableListOf<Request>()
        override fun complete(system: String, user: String, temperature: Double): String {
            val request = Request(system, JSONObject(user), temperature)
            requests.add(request)
            return respond(request, requests.lastIndex)
        }
        fun stages() = requests.map { it.stage }
    }
}
