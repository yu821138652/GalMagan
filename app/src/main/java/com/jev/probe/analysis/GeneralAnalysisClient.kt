package com.jev.probe.analysis

import com.jev.probe.core.Analysis
import com.jev.probe.core.Prefs
import com.jev.probe.jev.HttpJson
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.Route
import com.jev.probe.jev.RequestCancellation
import org.json.JSONArray
import org.json.JSONObject

class GeneralAnalysisClient(private val prefs: Prefs, private val cancellation: RequestCancellation = RequestCancellation()) {
    // Freeze the route for this round, even if settings change during a request.
    private val endpoint = prefs.replyEndpoint()
    private val key = prefs.replyKey
    private val model = prefs.replyModel
    private val useJev = prefs.analysisMode == Prefs.MODE_JEV_ASSISTED

    fun analyze(input: ConversationInput, isActive: () -> Boolean,
                onProgress: (String) -> Unit, onSituation: (Analysis) -> Unit): Analysis {
        if (!isActive()) throw java.util.concurrent.CancellationException("本次分析已取消")
        var referenceNote = ""
        val reference = if (useJev) {
            onProgress("正在读取可选 Jev 参考…")
            if (prefs.judgeKey.isBlank()) {
                referenceNote = "Jev 未配置，本轮已由通用模型独立完成。"
                null
            } else {
                val result = JudgeClient(prefs, isActive, readTimeoutMs = 15_000, maxAttempts = 1, cancellation = cancellation).judge(input.snapshot, input.relationship,
                    com.jev.probe.core.kb.ChatContext(null, input.history, emptyList()))
                if (result.error != null) {
                    referenceNote = "Jev 辅助不可用，本轮已由通用模型独立完成。"
                    null
                } else result.dangerLevel?.let { "Jev风险参考：${it.score}/9；仅供参考，须以原始聊天事实独立判断。" }
            }
        } else null
        val transport = ChatTransport { system, user, temperature ->
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", temperature)
            val response = HttpJson.post(endpoint, key, body, Route.GENERAL, HttpJson.headersFor(endpoint),
                readTimeoutMs = 60_000, maxAttempts = 1, isActive = isActive, cancellation = cancellation)
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = message.opt("content")
            when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull {
                    content.optJSONObject(it)?.takeIf { block -> block.optString("type") == "text" }?.optString("text")
                }.joinToString("")
                else -> throw IllegalArgumentException("通用模型未返回文本，请检查模型与接口")
            }
        }
        val result = GeneralModelPipeline(transport).run(input, reference, isActive, onProgress, onSituation)
        return result.copy(reviewSummary = listOf(result.reviewSummary, referenceNote).filter { it.isNotBlank() }.joinToString("\n"))
    }
}
