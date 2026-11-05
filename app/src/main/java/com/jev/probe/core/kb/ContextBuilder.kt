package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs

/**
 * Turns "what is on screen right now" into the extra context one analysis gets:
 * which contact this is, their older history, and which knowledge notes apply.
 *
 * Deliberately dumb and cheap (v1.3 revision): exact name/alias matching, plain
 * substring note matching, a hard character budget. No scoring, no embeddings,
 * no auto-created contacts, no network.
 */
object ContextBuilder {

    /** Notes + history together may not exceed this many characters. */
    // Keep enough room for a useful recent conversation. The model's current
    // window is capped separately at 100 messages; this budget controls older
    // per-contact history and notes.
    const val BUDGET_CHARS = 6000

    /** At most this many matched (non-always-on) notes. */
    const val MAX_HIT_NOTES = 5

    /** Only the last few messages are searched for note keywords. */
    private const val MATCH_WINDOW = 6

    /**
     * @param app package name of the foreground chat app (may be blank).
     * @return context for this snapshot; every field may be empty, which is the
     *         normal state before the user has built a knowledge base.
     */
    fun build(
        context: Context,
        snapshot: ChatSnapshot,
        app: String,
        prefs: Prefs,
        useContactHistory: Boolean = true,
        selectedContactId: String? = null
    ): ChatContext {
        val store = KbStore.get(context)
        val title = snapshot.title ?: ""

        // 1. Contact — matched only, never created here.
        val contact = if (!useContactHistory) null
            else if (selectedContactId != null) store.contact(selectedContactId) else store.findContact(title, app)

        // 2. History — recorded and injected only with the user's opt-in.
        // Selected contact identity scopes a local history log. Do not let a
        // legacy opt-in toggle silently prevent history from being sent.
        val history = if (contact != null)
            historyFor(store, contact, snapshot, app, prefs) else emptyList()

        // 3. Notes — always-on ones plus keyword hits.
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = enabled.filter { it.alwaysOn }
        val hits = matchNotes(enabled.filter { !it.alwaysOn }, snapshot)

        // 4. Style: contact samples take priority; global samples fill gaps.
        val style = if (prefs.styleLearning) styleFor(store, contact) else null

        // 5. Budget: always-on notes are exempt; the rest share BUDGET_CHARS,
        //    dropping oldest history first, then whole notes (never half a note).
        val trimmedHistory = ArrayList(history)
        val trimmedHits = ArrayList(hits)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHistory.isNotEmpty())
            trimmedHistory.removeAt(0)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHits.isNotEmpty())
            trimmedHits.removeAt(trimmedHits.size - 1)

        Log.d(TAG, "context: contact=${contact != null} notes=${alwaysOn.size + trimmedHits.size} " +
            "history=${trimmedHistory.size}")
        return ChatContext(contact, trimmedHistory, alwaysOn + trimmedHits, style)
    }

    /** Persist a newly recognized visible window immediately, before analysis. */
    fun recordVisible(context: Context, snapshot: ChatSnapshot, app: String, selectedContactId: String? = null): Contact? {
        if (snapshot.messages.isEmpty() || !snapshot.atLatest) return null
        val store = KbStore.get(context)
        val contact = if (selectedContactId != null) store.contact(selectedContactId)
            else store.findContact(snapshot.title.orEmpty(), app)
        contact ?: return null
        store.appendLog(contact.id, snapshot.messages.map {
            LogEntry(it.side, it.text, 0L, app)
        })
        return contact
    }

    private fun styleFor(store: KbStore, contact: Contact?): StyleProfile? {
        val profile = contact?.let { store.styleProfile(it.id)?.takeIf { profile -> !profile.isEmpty() } }
            ?: store.styleProfile(null)
        return profile?.takeIf { !it.isEmpty() }
    }

    /** Remove only the saved tail overlapping this screen, preserving earlier repeats. */
    private fun historyFor(
        store: KbStore,
        contact: Contact,
        snapshot: ChatSnapshot,
        app: String,
        prefs: Prefs
    ): List<LogEntry> {
        val n = prefs.contextHistoryCount.coerceIn(0, 100)
        if (n == 0) return emptyList()
        val environmentHistory = store.allLog(contact.id, app)
        val visibleKeys = snapshot.messages.map { it.side to it.text }
        val storedKeys = environmentHistory.map { it.side to it.text }
        val overlap = (minOf(storedKeys.size, visibleKeys.size) downTo 1).firstOrNull { count ->
            val tail = storedKeys.takeLast(count)
            (0..visibleKeys.size - count).any { start -> visibleKeys.subList(start, start + count) == tail }
        } ?: 0
        val onScreenIds = environmentHistory.takeLast(overlap).map { it.id }.toHashSet()
        // Filter the widest window FIRST, then take n. The other order lets the
        // messages currently on screen eat into the quota — ask for 30 lines of
        // history, get 30 minus however many are already visible.
        // Storage remains isolated by contact + app, but analysis intentionally
        // sees this person's recent history across all chat environments.
        return store.recentLog(contact.id, KbStore.MAX_LOG, null)
            .filter { it.id !in onScreenIds }
            .takeLast(n)
    }

    /**
     * A note matches when any of its tags, or its title, appears in the
     * conversation title or in the last few messages. Newest notes win the
     * [MAX_HIT_NOTES] cap.
     */
    private fun matchNotes(candidates: List<Note>, snapshot: ChatSnapshot): List<Note> {
        if (candidates.isEmpty()) return emptyList()
        val haystack = buildString {
            append(KbStore.normalizeText(snapshot.title))
            snapshot.messages.takeLast(MATCH_WINDOW).forEach {
                append('\n').append(KbStore.normalizeText(it.text))
            }
        }
        if (haystack.isBlank()) return emptyList()
        return candidates
            .filter { note ->
                (note.tags + note.title).any { raw ->
                    val needle = KbStore.normalizeText(raw)
                    needle.isNotEmpty() && haystack.contains(needle)
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_HIT_NOTES)
    }

    private fun cost(notes: List<Note>, history: List<LogEntry>): Int =
        notes.sumOf { it.title.length + it.content.length + 2 } +
            history.sumOf { it.text.length + 3 }

    private const val TAG = "JEVASSIST"
    private const val MAX_STYLE_EXAMPLES = 12
}
