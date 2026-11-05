package com.jev.probe.core

import android.graphics.Rect
import com.jev.probe.analysis.Situation

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(val side: String, val text: String)

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    /** False when the chat list can still scroll down to newer messages. */
    val atLatest: Boolean = true
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of this conversation title and its last few messages. */
    fun signature(): String =
        "${title.orEmpty()}\u001f" + messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice? = null,
    val dangerLevel: Score? = null,
    val sheNeeds: Choice? = null,
    val shouldReplyNow: Double? = null,
    val bestAction: Choice? = null,
    val tensionResolved: Double? = null,
    val literalQuestion: Double? = null,
    val rankedReplies: List<RankedReply> = emptyList(),
    val latencyMs: Long = 0,
    val error: String? = null,
    val continuation: Boolean = false,
    val situation: Situation? = null,
    val reviewSummary: String = ""
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double = 0.0, val approach: String = "")
