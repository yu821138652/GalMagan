package com.jev.probe.analysis

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.LogEntry
import com.jev.probe.testing.TestContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Opt-in live evaluation: save outputs beside human review criteria, not an invented accuracy score. */
class LiveModelEvaluationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun evaluateChineseCasesForHumanReview() {
        val key = System.getenv("GALMAGAN_EVAL_KEY").orEmpty()
        val base = System.getenv("GALMAGAN_EVAL_BASE_URL").orEmpty()
        assumeTrue("未提供实测接口，跳过真实模型质量评估", key.isNotBlank() && base.isNotBlank())
        val prefs = Prefs(TestContext(temp.root), "live-evaluation").apply {
            replyKey = key
            replyBaseUrl = base
            replyModel = System.getenv("GALMAGAN_EVAL_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-6-luna"
            analysisMode = Prefs.MODE_GENERAL
        }
        require(prefs.analysisConfigError() == null) { "实测接口配置不完整" }
        val suite = javaClass.getResourceAsStream("/analysis-cases-zh.json")!!.bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val cases = suite.getJSONArray("cases")
        val results = JSONArray()
        val output = File("build/reports/general-model/live-evaluation.json")
        output.parentFile!!.mkdirs()
        fun save() {
            output.writeText(JSONObject().put("model", prefs.replyModel).put("quality_passed", JSONObject.NULL)
                .put("review_status", "需要人工按每例观察点评审，结构通过不等于质量通过")
                .put("results", results).toString(2), Charsets.UTF_8)
        }
        repeat(cases.length()) { i ->
            val case = cases.getJSONObject(i)
            val messages = case.getJSONArray("messages")
            val history = case.getJSONArray("history")
            val adjustments = case.getJSONArray("adjustments")
            val input = ConversationInput(
                ChatSnapshot(null, (0 until messages.length()).map { j ->
                    messages.getJSONObject(j).let { Msg(it.getString("from"), it.getString("text")) }
                }),
                (0 until history.length()).map { j -> history.getJSONObject(j).let {
                    LogEntry(it.getString("from"), it.getString("text"), it.optLong("original_time_ms", 0), it.getString("environment"))
                } }, case.getString("environment"), case.getString("relationship"), case.getString("background"),
                case.getString("style"), ChatDirection.valueOf(case.getString("direction")),
                (0 until adjustments.length()).map { j -> adjustments.getString(j) })
            val record = JSONObject().put("case_id", case.getString("id")).put("category", case.getString("category"))
                .put("quality_expectations", case.getJSONArray("qualityExpectations")).put("human_quality_result", JSONObject.NULL)
            try {
                val result = GeneralAnalysisClient(prefs).analyze(input, { true }, {}, {})
                record.put("analysis", result.situation!!.json()).put("review", result.reviewSummary)
                    .put("candidates", JSONArray(result.rankedReplies.map { JSONObject().put("text", it.text).put("approach", it.approach) }))
            } catch (e: Exception) { record.put("request_error", e.message?.replace(key, "[密钥已隐藏]")) }
            results.put(record)
            save()
        }
    }
}
