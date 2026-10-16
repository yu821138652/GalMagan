package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Note / contact / history counts, for the settings screen. */
data class KbCounts(val notes: Int, val contacts: Int, val logLines: Int)

/**
 * The knowledge-base store: three kinds of JSON file under `filesDir/kb`.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/<contactId>.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches logcat — only counts and lengths.
 */
class KbStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val root: File get() = File(app.filesDir, "kb")
    private val notesFile: File get() = File(root, "notes.json")
    private val contactsFile: File get() = File(root, "contacts.json")
    private val globalStyleFile: File get() = File(root, "style-global.json")
    private fun logFile(contactId: String) = File(File(root, "logs"), "$contactId.json")
    private fun screenKey(contactId: String, environment: String) = "$contactId:$environment"
    private fun screenFile(contactId: String, environment: String) =
        File(File(root, "logs"), "$contactId.${environment.ifBlank { "unknown" }}.screen.json")
    private fun styleFile(contactId: String) = File(File(root, "styles"), "$contactId.json")

    private var notesCache: MutableList<Note>? = null
    private var contactsCache: MutableList<Contact>? = null
    private val logCache = HashMap<String, MutableList<LogEntry>>()

    /** Per contact: the comparison keys of the last screen written. See [appendLog]. */
    private val lastScreenCache = HashMap<String, List<String>>()

    /** Save one synthesized style description, replacing the previous one. */
    fun saveStyleProfile(contactId: String?, profile: StyleProfile): Boolean = synchronized(lock) {
        val file = contactId?.takeIf { it.isNotBlank() }?.let { styleFile(it) } ?: globalStyleFile
        writeAtomic(file, JSONObject()
            .put("source", profile.source)
            .put("summary", profile.summary.trim())
            .put("updatedAt", System.currentTimeMillis())
            .toString())
    }

    fun styleProfile(contactId: String?): StyleProfile? = synchronized(lock) {
        val file = contactId?.takeIf { it.isNotBlank() }?.let { styleFile(it) } ?: globalStyleFile
        readStyleProfile(file)
    }

    /** Legacy raw samples are available only as input to the first synthesis. */
    fun legacyStyleExamples(contactId: String?): List<String> = synchronized(lock) {
        val file = contactId?.takeIf { it.isNotBlank() }?.let { styleFile(it) } ?: globalStyleFile
        val loaded = readJsonArray(file)
        loaded.arr?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
    }

    fun outgoingMessages(contactId: String?): List<String> = synchronized(lock) {
        if (contactId != null) {
            loadLog(contactId).filter { it.side == "me" }.map { it.text }
        } else {
            loadContacts().flatMap { c -> loadLog(c.id).filter { it.side == "me" }.map { it.text } }
        }
    }

    fun clearStyleProfile(contactId: String?) = synchronized(lock) {
        val file = contactId?.takeIf { it.isNotBlank() }?.let { styleFile(it) } ?: globalStyleFile
        runCatching { file.delete() }
        Unit
    }

    fun clearAllStyleProfiles() = synchronized(lock) {
        runCatching { globalStyleFile.delete() }
        runCatching { File(root, "styles").deleteRecursively() }
        Unit
    }

    // ------------------------------------------------------------------ notes

    fun notes(): List<Note> = synchronized(lock) { loadNotes().toList() }

    fun note(id: String): Note? = synchronized(lock) { loadNotes().firstOrNull { it.id == id } }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    fun saveNote(note: Note): Boolean = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null   // memory must not claim a write that failed
        ok
    }

    fun deleteNote(id: String): Boolean = synchronized(lock) {
        val list = loadNotes()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null
        ok
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun saveContact(c: Contact): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        val stamped = c.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (!ok) contactsCache = null
        ok
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        var ok = true
        if (list.removeAll { it.id == id }) {
            ok = writeAtomic(contactsFile, contactsJson(list))
            if (!ok) contactsCache = null
        }
        clearLog(id)
        runCatching { styleFile(id).delete() }
        ok
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    fun findContact(title: String, app: String): Contact? {
        synchronized(lock) {
            val want = normalizeName(title)
            if (want.isEmpty()) return null
            val hits = loadContacts().filter { c ->
                normalizeName(c.name) == want || c.aliases.any { normalizeName(it) == want }
            }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { app.isNotBlank() && it.apps.contains(app) } ?: hits.first()
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     */
    fun saveOrMergeContact(title: String, app: String): String {
        val display = displayName(title)
        if (display.isEmpty()) return "当前会话没有标题，存不了"
        val existing = findContact(title, app)
        if (existing == null) {
            val aliases = if (displayName(title) != title.trim()) listOf(title.trim()) else emptyList()
            saveContact(Contact(
                id = newId(),
                name = display,
                aliases = aliases,
                apps = if (app.isBlank()) emptyList() else listOf(app)
            ))
            return "已存为联系人「${display}」"
        }
        val apps = if (app.isBlank() || existing.apps.contains(app)) existing.apps else existing.apps + app
        val raw = title.trim()
        val known = (listOf(existing.name) + existing.aliases).map { normalizeName(it) }
        val aliases = if (raw.isNotEmpty() && normalizeName(raw) !in known)
            existing.aliases + raw else existing.aliases
        if (apps == existing.apps && aliases == existing.aliases)
            return "联系人「${existing.name}」已存在"
        saveContact(existing.copy(apps = apps, aliases = aliases))
        return "已并入联系人「${existing.name}」"
    }

    // ---------------------------------------------------------------- history

    /**
     * Append one screenful of messages, keeping only the newest [MAX_LOG].
     *
     * The unit is a SEQUENCE, not a set of lines. A capture gives us the whole
     * visible screen S, top to bottom; P is whatever screen we last wrote for
     * this contact. Two identical short lines on one screen are two positions and
     * get two entries — they are not folded together, and nothing is dropped for
     * being "too short to dedupe on" or for having been said before.
     *
     * The rules, in order:
     *  - S equals P            → the same screen again, write nothing.
     *  - log is empty          → write all of S.
     *  - log tail matches the first k lines of S (k > 0) → the screen scrolled by
     *    (S.size - k) lines; append only that new tail.
     *  - k is 0 and S shares nothing with P → the user scrolled up into old
     *    messages we already hold; this round writes nothing rather than
     *    duplicating history at the end of the file.
     *  - anything else         → append all of S.
     *
     * @param screenBatch true for a capture (the rules above). False for a
     *        deliberate single-entry injection that is NOT a screen read, which
     *        is appended as-is.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>, screenBatch: Boolean = true): Boolean {
        return synchronized(lock) {
            entries.groupBy { it.app }.map { (environment, batch) ->
                appendEnvironment(contactId, environment, batch, screenBatch)
            }.all { it }
        }
    }

    private fun appendEnvironment(contactId: String, environment: String, entries: List<LogEntry>, screenBatch: Boolean): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val screen = entries.filter { it.text.isNotBlank() }
            if (screen.isEmpty()) return true
            val list = loadLog(contactId).filter { it.app == environment }.toMutableList()
            val keys = screen.map { key(it.side, it.text) }
            val prev = if (screenBatch) loadLastScreen(contactId, environment) else emptyList()

            // Same screen as last time: nothing happened worth recording.
            if (screenBatch && prev.isNotEmpty() && prev == keys) return true

            // Preloading can leave the newest screen away from the log tail.
            // Treat an exact sequence found anywhere in the log as an old page.
            if (screenBatch && containsSequence(list.map { key(it.side, it.text) }, keys)) {
                saveLastScreen(contactId, environment, keys)
                return true
            }

            // How much of S the log already ends with.
            var k = 0
            val maxK = minOf(list.size, keys.size)
            for (cand in maxK downTo 1) {
                var match = true
                for (i in 0 until cand) {
                    val e = list[list.size - cand + i]
                    if (key(e.side, e.text) != keys[i]) { match = false; break }
                }
                if (match) { k = cand; break }
            }

            val tail: List<LogEntry> = when {
                !screenBatch -> screen
                // A manual edit changes the stored text, but not the source
                // screen. Compare against that screen to keep corrections.
                screenBatch && prev.isNotEmpty() && screenOverlap(prev, keys) > 0 ->
                    screen.drop(screenOverlap(prev, keys))
                list.isEmpty() -> screen
                k > 0 -> screen.drop(k)
                // Nothing in common with the screen we last wrote → we are looking
                // at older messages, not newer ones. Leave the log alone.
                prev.isNotEmpty() && keys.none { it in prev } -> {
                    Log.d(TAG, "appendLog contact=$contactId skipped: scrolled off the last screen")
                    return true
                }
                else -> {
                    val known = list.map { key(it.side, it.text) }.toHashSet()
                    screen.filter { key(it.side, it.text) !in known }
                }
            }
            if (tail.isEmpty()) {
                if (screenBatch) saveLastScreen(contactId, environment, keys)
                return true
            }

            list.addAll(tail)
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = saveEnvironment(contactId, environment, list)
            if (ok && screenBatch) saveLastScreen(contactId, environment, keys)
            Log.d(TAG, "appendLog contact=$contactId added=${tail.size} overlap=$k total=${list.size} ok=$ok")
            return ok
        }
    }

    /** The newest [n] entries, oldest first. */
    fun recentLog(contactId: String, n: Int, environment: String? = null): List<LogEntry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            val all = loadLog(contactId)
            if (environment != null) {
                val list = all.filter { it.app == environment }
                return if (list.size <= n) list.toList()
                else list.subList(list.size - n, list.size).toList()
            }

            // The on-disk format keeps each environment in its own contiguous
            // block so updating WeChat does not rewrite the Xiaohongshu block.
            // A plain takeLast(n) would therefore hide the other environment.
            // Original chat timestamps are not available, so preserve each
            // environment's internal order and interleave their newest windows
            // without pretending to know the cross-app chronological order.
            val byEnvironment = all.groupBy { it.app }.values
                .map { entries -> entries.takeLast(n).toMutableList() }
                .filter { it.isNotEmpty() }
            if (byEnvironment.isEmpty()) return emptyList()
            val result = ArrayList<LogEntry>(minOf(n, all.size))
            var offsetFromNewest = 0
            while (result.size < n && byEnvironment.any { offsetFromNewest < it.size }) {
                byEnvironment.forEach { entries ->
                    val index = entries.lastIndex - offsetFromNewest
                    if (index >= 0 && result.size < n) {
                        result.add(entries[index])
                    }
                }
                offsetFromNewest++
            }
            result.reverse()
            return result
        }
    }

    /**
     * Insert an older screen before the retained history. Used by the manual
     * history pre-collector, which scrolls upward instead of toward newer data.
     * The overlapping suffix/prefix is kept only once.
     */
    fun prependLog(contactId: String, entries: List<LogEntry>): Boolean {
        return synchronized(lock) {
            entries.groupBy { it.app }.map { (environment, batch) ->
                prependEnvironment(contactId, environment, batch)
            }.all { it }
        }
    }

    private fun prependEnvironment(contactId: String, environment: String, entries: List<LogEntry>): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val screen = entries.filter { it.text.isNotBlank() }
            if (screen.isEmpty()) return true
            val list = loadLog(contactId).filter { it.app == environment }.toMutableList()
            if (list.isEmpty()) {
                list.addAll(screen)
            } else {
                val existingKeys = list.map { key(it.side, it.text) }
                val incomingKeys = screen.map { key(it.side, it.text) }
                if (containsSequence(existingKeys, incomingKeys)) return true
                var overlap = 0
                val max = minOf(existingKeys.size, incomingKeys.size)
                for (candidate in max downTo 1) {
                    if (incomingKeys.takeLast(candidate) == existingKeys.take(candidate)) {
                        overlap = candidate
                        break
                    }
                }
                val known = existingKeys.toHashSet()
                val older = if (overlap > 0) screen.dropLast(overlap)
                    else screen.filter { key(it.side, it.text) !in known }
                if (older.isNotEmpty()) list.addAll(0, older)
            }
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = saveEnvironment(contactId, environment, list)
            Log.d(TAG, "prependLog contact=$contactId added=${screen.size} ok=$ok")
            return ok
        }
    }

    /** Full retained history for inspection; oldest first. */
    fun allLog(contactId: String, environment: String? = null): List<LogEntry> = synchronized(lock) {
        loadLog(contactId).filter { environment == null || it.app == environment }
    }

    fun logSize(contactId: String, environment: String? = null): Int = allLog(contactId, environment).size

    fun updateLogEntry(contactId: String, id: String, side: String, text: String): Boolean = synchronized(lock) {
        if (side !in listOf("me", "other") || text.isBlank()) return@synchronized false
        val entry = loadLog(contactId).firstOrNull { it.id == id } ?: return@synchronized false
        val entries = loadLog(contactId).filter { it.app == entry.app }.map {
            if (it.id == id) it.copy(side = side, text = text.trim()) else it
        }
        saveEnvironment(contactId, entry.app, entries)
    }

    fun deleteLogEntry(contactId: String, id: String): Boolean = deleteLogEntries(contactId, setOf(id))

    fun deleteLogEntries(contactId: String, ids: Set<String>): Boolean = synchronized(lock) {
        val original = loadLog(contactId)
        if (ids.isEmpty() || !original.any { it.id in ids }) return@synchronized false
        val remaining = original.filter { it.id !in ids }
        val ok = writeAtomic(logFile(contactId), logJson(remaining))
        if (ok) logCache[contactId] = remaining.toMutableList() else logCache.remove(contactId)
        ok
    }

    private fun screenOverlap(previous: List<String>, current: List<String>): Int =
        (minOf(previous.size, current.size) downTo 1).firstOrNull {
            previous.takeLast(it) == current.take(it)
        } ?: 0

    fun historyEnvironments(contactId: String): List<String> = synchronized(lock) {
        loadLog(contactId).map { it.app }.distinct()
    }

    // Keep the existing file format so older records need no destructive migration.
    // Each environment is merged, deduplicated and capped independently.
    private fun saveEnvironment(contactId: String, environment: String, entries: List<LogEntry>): Boolean {
        val merged = loadLog(contactId).filter { it.app != environment } + entries
        val ok = writeAtomic(logFile(contactId), logJson(merged))
        if (ok) logCache[contactId] = merged.toMutableList() else logCache.remove(contactId)
        return ok
    }

    fun clearLog(contactId: String) = synchronized(lock) {
        logCache.remove(contactId)
        lastScreenCache.keys.removeAll { it.startsWith("$contactId:") }
        runCatching { logFile(contactId).delete() }
        File(root, "logs").listFiles()?.filter {
            it.name.startsWith("$contactId.") && it.name.endsWith(".screen.json")
        }?.forEach { it.delete() }
        Unit
    }

    /**
     * The (side, text) sequence of the last screen written for this contact, as
     * comparison keys. Kept on disk as well as in memory so that re-opening a
     * chat that has not moved since does not append the same screen again.
     */
    private fun loadLastScreen(contactId: String, environment: String): List<String> {
        val cacheKey = screenKey(contactId, environment)
        lastScreenCache[cacheKey]?.let { return it }
        val out = ArrayList<String>()
        val loaded = readJsonArray(screenFile(contactId, environment))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
        }
        if (loaded.trustworthy) lastScreenCache[cacheKey] = out
        return out
    }

    private fun saveLastScreen(contactId: String, environment: String, keys: List<String>) {
        val cacheKey = screenKey(contactId, environment)
        lastScreenCache[cacheKey] = keys
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        if (!writeAtomic(screenFile(contactId, environment), arr.toString())) lastScreenCache.remove(cacheKey)
    }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return haystack.windowed(needle.size).any { it == needle }
    }

    // ------------------------------------------------------------------ admin

    fun counts(): KbCounts = synchronized(lock) {
        val contacts = loadContacts()
        var lines = 0
        contacts.forEach { lines += loadLog(it.id).size }
        KbCounts(loadNotes().size, contacts.size, lines)
    }

    /**
     * Wipe every knowledge-base file. Deletes only `filesDir/kb` — API keys,
     * whitelist and every other SharedPreferences value are untouched.
     */
    fun clearAll() = synchronized(lock) {
        notesCache = null
        contactsCache = null
        logCache.clear()
        lastScreenCache.clear()
        runCatching { root.deleteRecursively() }
        Log.i(TAG, "kb cleared")
        Unit
    }

    // ------------------------------------------------------------------ io

    private fun loadNotes(): MutableList<Note> {
        notesCache?.let { return it }
        val list = ArrayList<Note>()
        val loaded = readJsonArray(notesFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Note(
                    id = o.optString("id").ifBlank { newId() },
                    title = o.optString("title"),
                    content = o.optString("content"),
                    tags = strList(o.optJSONArray("tags")),
                    alwaysOn = o.optBoolean("alwaysOn", false),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) notesCache = list
        return list
    }

    private fun readStyleProfile(file: File): StyleProfile? {
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val obj = JSONObject(text)
            StyleProfile(
                source = obj.optString("source", "").ifBlank { "本地风格" },
                summary = obj.optString("summary", ""),
                updatedAt = obj.optLong("updatedAt", 0L)
            ).takeIf { it.summary.isNotBlank() }
        }.getOrNull()
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        val loaded = readJsonArray(contactsFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Contact(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name"),
                    aliases = strList(o.optJSONArray("aliases")),
                    apps = strList(o.optJSONArray("apps")),
                    relationship = o.optString("relationship"),
                    notes = o.optString("notes"),
                    autoSummary = o.optString("autoSummary"),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) contactsCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        val loaded = readJsonArray(logFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(LogEntry(
                    side = o.optString("side", "other"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0L),
                    app = o.optString("app"),
                    id = o.optString("id").ifBlank { newId() }
                ))
            }
        }
        if (loaded.trustworthy) logCache[contactId] = list
        return list
    }

    private fun notesJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("alwaysOn", n.alwaysOn)
                .put("enabled", n.enabled)
                .put("updatedAt", n.updatedAt))
        }
        return arr.toString()
    }

    private fun contactsJson(list: List<Contact>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("aliases", JSONArray(c.aliases))
                .put("apps", JSONArray(c.apps))
                .put("relationship", c.relationship)
                .put("notes", c.notes)
                .put("autoSummary", c.autoSummary)
                .put("updatedAt", c.updatedAt))
        }
        return arr.toString()
    }

    private fun logJson(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id)
                .put("side", e.side)
                .put("text", e.text)
                .put("ts", e.ts)
                .put("app", e.app))
        }
        return arr.toString()
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private class Loaded(val arr: JSONArray?, val trustworthy: Boolean)

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private val unreadable = HashSet<String>()

    private fun readJsonArray(f: File): Loaded {
        if (!f.exists()) { unreadable.remove(f.absolutePath); return Loaded(null, true) }
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            unreadable.remove(f.absolutePath)
            Loaded(arr, true)
        } catch (e: Exception) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            val backup = File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}")
            val kept = runCatching { f.renameTo(backup) }.getOrDefault(false)
            if (kept) unreadable.remove(f.absolutePath) else unreadable.add(f.absolutePath)
            Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName} preserved=$kept")
            Loaded(null, kept)
        }
    }

    /**
     * Temp file + rename, so a crash never leaves a half-written document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. Returns false when the data did not reach disk; callers
     * drop their cache so the next read goes back to the file.
     */
    private fun writeAtomic(f: File, text: String): Boolean {
        if (f.absolutePath in unreadable) {
            Log.w(TAG, "refusing to overwrite unparsable ${f.name}")
            return false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(f)) return true
            // Same-directory rename should not fail. If it somehow does, an
            // in-place overwrite is the only way left — not atomic, so say so.
            Log.w(TAG, "rename failed, overwriting ${f.name} in place")
            f.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun strList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun key(side: String, text: String) = side + "\u0000" + text

    companion object {
        private const val TAG = "JEVASSIST"
        const val MAX_LOG = 300

        @Volatile private var instance: KbStore? = null

        fun get(context: Context): KbStore =
            instance ?: synchronized(this) {
                instance ?: KbStore(context).also { instance = it }
            }

        fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 12)

        /**
         * Compile a pattern without ever taking the class down with it. A
         * `Regex(...)` straight in a `val` runs during `<clinit>`, so one bad
         * pattern turns into `ExceptionInInitializerError` and every call into
         * [KbStore] dies with it — which is exactly what happened on device
         * (Android's ICU engine rejected the old member-count pattern). A null
         * here only means that one cleanup step is skipped.
         */
        private fun safeRegex(pattern: String): Regex? =
            runCatching { Regex(pattern) }.getOrElse {
                Log.w(TAG, "regex init failed: ${it.javaClass.simpleName} ${it.message ?: ""}")
                null
            }

        private val ZERO_WIDTH = safeRegex("[\\u200B-\\u200D\\uFEFF]")

        /**
         * Trailing group member count. Written as an alternation of escaped code
         * points rather than a character class holding brackets: ICU on device
         * read `[(...)]` as an unterminated class ("missing closing bracket").
         * No literal full-width bracket in the source, on purpose.
         */
        private val TRAILING_COUNT =
            safeRegex("\\s*(?:\\(|\\uFF08)\\s*\\d+\\s*(?:\\)|\\uFF09)\\s*$")

        private fun stripZeroWidth(s: String): String = ZERO_WIDTH?.replace(s, "") ?: s

        private fun stripTrailingCount(s: String): String =
            TRAILING_COUNT?.replace(s, "")?.trim() ?: s

        /**
         * Name key for matching: trimmed, zero-width characters removed, the
         * group member count `(12)` (half- or full-width) dropped, lower-cased.
         * If the patterns failed to compile this degrades to trim + lowercase.
         */
        fun normalizeName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val t = stripTrailingCount(stripZeroWidth(s).trim())
            return t.trim().lowercase()
        }

        /** Same cleanup as [normalizeName] but keeps the original casing, for display. */
        fun displayName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripTrailingCount(stripZeroWidth(s).trim()).trim()
        }

        /** Loose key for substring matching (no member-count stripping). */
        fun normalizeText(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripZeroWidth(s).trim().lowercase()
        }
    }
}
