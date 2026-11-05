package com.jev.probe.analysis

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ChatEnvironment
import com.jev.probe.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject

enum class ChatDirection(val label: String, val guidance: String) {
    DEFAULT("默认", "不额外指定方向，由聊天事实、背景和本次补充决定策略。"),
    SOOTHE("安抚与化解冲突", "关注情绪、澄清误会、缓和冲突，不无条件认错，不编造过错或承诺。"),
    CONTINUE("自然延续话题", "自然承接、补充、分享或追问，避免机械道歉和过度分析。"),
    CONNECT("增进好感", "在关系阶段和对方意愿允许时增加关心和适度亲近；真实冲突、明确拒绝和边界优先，不强行暧昧。")
}

data class ReplyStrategy(val goal: String, val actions: List<String>, val cautions: List<String>) {
    fun json(): JSONObject = JSONObject().put("goal", goal)
        .put("actions", JSONArray(actions)).put("cautions", JSONArray(cautions))
}

data class Situation(
    val topic: String,
    val unresolved: List<String>,
    val facts: List<String>,
    val interpretations: List<String>,
    val missingContext: List<String>,
    val riskLevel: Int,
    val riskReason: String,
    val strategy: ReplyStrategy,
    val correctedFacts: List<String>,
    val expressionPreferences: List<String>
) {
    fun json(): JSONObject = JSONObject().put("topic", topic)
        .put("unresolved", JSONArray(unresolved)).put("facts", JSONArray(facts))
        .put("interpretations", JSONArray(interpretations)).put("missing_context", JSONArray(missingContext))
        .put("risk_level", riskLevel).put("risk_reason", riskReason).put("strategy", strategy.json())
        .put("corrected_facts", JSONArray(correctedFacts))
        .put("expression_preferences", JSONArray(expressionPreferences))
}

/** Only the selected contact's context is passed here; adjustments never enter the message arrays. */
data class ConversationInput(
    val snapshot: ChatSnapshot,
    val history: List<LogEntry>,
    val environment: String,
    val relationship: String,
    val background: String,
    val style: String,
    val direction: ChatDirection,
    val adjustments: List<String>,
    val knowledgeNotesCount: Int = 0,
    val previousAnalysis: Situation? = null,
    val previousReplies: List<RankedReply> = emptyList(),
    val adjustmentRounds: List<AdjustmentRound> = emptyList()
) {
    fun withoutAdjustments(): ConversationInput = copy(adjustments = emptyList(), previousAnalysis = null,
        previousReplies = emptyList(), adjustmentRounds = emptyList())

    fun json(includeStyle: Boolean): JSONObject {
        val current = JSONArray()
        snapshot.messages.forEachIndexed { i, msg ->
            current.put(JSONObject().put("id", "current-${i + 1}").put("from", msg.side)
                .put("text", msg.text).put("environment", ChatEnvironment.label(environment)))
        }
        val past = JSONArray()
        history.forEach { entry ->
            past.put(JSONObject().put("from", entry.side).put("text", entry.text)
                .put("environment", ChatEnvironment.label(entry.app)).apply {
                    if (entry.ts > 0) put("original_time_ms", entry.ts)
                })
        }
        return JSONObject().put("relationship", relationship).put("background", background)
            .put("history", past).put("current_messages", current)
            .put("capture_note", snapshot.note.orEmpty())
            .put("direction", JSONObject().put("name", direction.label).put("guidance", direction.guidance))
            .put("session_adjustments", JSONArray(adjustments)).apply {
                if (includeStyle && style.isNotBlank()) put("my_wording_style", style)
                if (previousAnalysis != null) put("previous_assistant_output", JSONObject()
                    .put("analysis", previousAnalysis.json())
                    .put("candidates", JSONArray(previousReplies.map { JSONObject().put("text", it.text).put("approach", it.approach) })))
                if (adjustmentRounds.isNotEmpty()) put("adjustment_rounds", JSONArray(adjustmentRounds.map { it.json() }))
            }
    }

    fun visibleContext(): List<Msg> = history.map {
        Msg(it.side, "[${ChatEnvironment.label(it.app)}] ${it.text}")
    } + snapshot.messages.map { Msg(it.side, "[${ChatEnvironment.label(environment)}] ${it.text}") }

    companion object {
        fun build(snapshot: ChatSnapshot, ctx: ChatContext?, environment: String, relationship: String,
                  direction: ChatDirection, adjustments: List<String>): ConversationInput {
            val current = snapshot.copy(messages = snapshot.messages.takeLast(100))
            val history = ctx?.history.orEmpty().takeLast((100 - current.messages.size).coerceAtLeast(0))
            return ConversationInput(current, history, environment,
                ctx?.contact?.relationship?.takeIf { it.isNotBlank() } ?: relationship,
                ctx?.background(relationship).orEmpty(), ctx?.style?.summary.orEmpty(), direction, adjustments.toList(), ctx?.notes?.size ?: 0)
        }
    }
}

data class AdjustmentRound(val userAdjustment: String, val priorAnalysis: Situation?, val priorReplies: List<RankedReply>) {
    fun json(): JSONObject = JSONObject().put("user_adjustment", userAdjustment)
        .put("prior_candidates", JSONArray(priorReplies.map { JSONObject().put("text", it.text).put("approach", it.approach) }))
        .apply { priorAnalysis?.let { put("prior_analysis", it.json()) } }
}

/** In-memory corrections are scoped to the selected identity, app and latest visible messages. */
class ConversationSession {
    private var identity: String? = null
    private var signature: String? = null
    private val turns = mutableListOf<String>()
    var direction: ChatDirection = ChatDirection.DEFAULT
    val adjustments: List<String> get() = turns.toList()

    fun bind(identity: String, signature: String): Boolean {
        val changed = this.identity != identity || this.signature != signature
        val discarded = changed && turns.isNotEmpty()
        if (changed) turns.clear()
        this.identity = identity
        this.signature = signature
        return discarded
    }

    fun add(text: String) {
        val value = text.trim()
        require(value.isNotBlank() && value.length <= 1000) { "补充说明请填写 1—1000 字" }
        require(turns.size < 12) { "本次调整已达 12 轮，请清除后重新分析" }
        turns.add(value)
    }

    fun clear() { turns.clear() }
    fun reset() { identity = null; signature = null; turns.clear(); direction = ChatDirection.DEFAULT }
}

object RiskScale {
    val levels = listOf(
        "轻松聊天或玩笑，没有抱怨、试探或催促。",
        "轻微打趣或小提醒，失当回复只会稍显尴尬。",
        "温和抱怨或提醒下次注意，后续仍友好、务实。",
        "明显不满，表达被忽略、遗忘或等待，但仍愿意沟通。",
        "出现讽刺、冷淡或警告，草率或假装确定的回复可能升级冲突。",
        "公开表达难过或不满，指责没有倾听或关心，需要认真回应。",
        "明确愤怒、责备，错误回应容易引发争吵。",
        "最后机会的警告，信任接近耗尽，继续交流附带改变要求。",
        "尚未撤回的最后通牒，例如分手、上报或终止合作，即使同时提出具体任务。",
        "关系正在破裂，明确结束、拒绝回复、删除联系人或激烈爆发。"
    )
    fun legend(): String = levels.mapIndexed { i, text -> "$i：$text" }.joinToString("\n")
}
