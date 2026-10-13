package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects new messages, runs the
 * appropriate analysis or continuation flow off the main thread, and drives
 * the floating overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(
        WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter(), XhsAdapter()
    ).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false

    /** Last known-good (non-transient) title per package. See [isTransientTitle]:
     *  a page like X's DM thread briefly shows "连接中…" as `snapshot.title`
     *  right after opening, which must never overwrite a real conversation
     *  title or get saved as a contact name. Never cleared on app switch — the
     *  next real title for that package simply replaces it. */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private data class ManualTitleOverride(val sourceTitle: String?, val correctedTitle: String)
    private val manualTitleOverrides: MutableMap<String, ManualTitleOverride> = HashMap()
    private val lastObservedTitles: MutableMap<String, String?> = HashMap()
    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null
    /** Explicit identity selected by the user; never inferred from WeChat UI text. */
    private var selectedContact: com.jev.probe.core.kb.Contact? = null
    private var defaultContextSelected = false
    private var historyImporting = false
    private var selectedHistoryEnvironment: String? = null
    private data class HistoryImportSession(
        val contact: com.jev.probe.core.kb.Contact,
        val environment: String,
        var pages: Int = 0,
        var added: Int = 0,
        var duplicates: Int = 0,
        var lastKeys: List<String> = emptyList(),
        var waitingSince: Long = SystemClock.elapsedRealtime(),
        var awaitingScroll: Boolean = false
    )
    private var historySession: HistoryImportSession? = null
    private val historyStep = Runnable { stepHistoryImport() }
    private val enabledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SETUP_HISTORY) {
                if (historyImporting) { overlay?.toast("请先停止当前预加载"); return }
                val contact = intent.getStringExtra(EXTRA_CONTACT_ID)?.let { KbStore.get(this@ChatCaptureService).contact(it) }
                if (contact != null) {
                    selectedContact = contact
                    defaultContextSelected = false
                    overlay?.showEnvironmentChooser(contact)
                }
                return
            }
            if (intent?.action != ACTION_SET_ENABLED) return
            val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
            if (!enabled) {
                stopHistoryImport()
                overlay?.hide()
                stopKeepAlive()
            } else {
                overlay?.showIdle(null)
                runCatching { KeepAliveService.start(this@ChatCaptureService) }
                main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 200)
            }
        }
    }

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false
    private var lastOcrGroupingNote: String? = null

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""
    private var lastEmptyTreeOcrRequestAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this)
        registerReceiver(enabledReceiver, IntentFilter(ACTION_SET_ENABLED).apply {
            addAction(ACTION_SETUP_HISTORY)
        }, RECEIVER_NOT_EXPORTED)
        if (prefs.enabled) overlay?.showIdle(null)
        else stopKeepAlive()
        overlay?.onManualAnalyze = {
            if (!historyImporting) {
                val root = chatRoot(selectedHistoryEnvironment ?: activePkg)
                val pkg = root?.packageName?.toString()
                val fresh = root?.let { adapters[pkg]?.extract(it, resources) }
                val available = fresh?.takeIf { it.messages.isNotEmpty() } ?: currentSnapshot
                when {
                    available == null || available.messages.isEmpty() -> overlay?.toast("请回到当前聊天页，尚未读取到消息")
                    fresh?.atLatest == false -> overlay?.toast("请先回到聊天底部，再分析最新对话")
                    else -> {
                        activePkg = pkg
                        val corrected = available.copy(title = if (defaultContextSelected) null else selectedContact?.name ?: available.title)
                        currentSnapshot = corrected; pendingSnapshot = corrected; runAnalysis()
                    }
                }
            }
        }
        overlay?.onListContacts = {
            runCatching { KbStore.get(this).contacts() }.getOrDefault(emptyList())
        }
        overlay?.onContactSelected = { contact ->
            selectedContact = contact
            defaultContextSelected = false
            selectedHistoryEnvironment = null
            overlay?.showEnvironmentChooser(contact)
        }
        overlay?.onEnvironmentSelected = { environment ->
            selectedHistoryEnvironment = environment
            activePkg = environment
            lastSignature = ""
            selectedContact?.let { contact ->
                overlay?.showContactHistory(contact, KbStore.get(this).allLog(contact.id, environment), environment)
            }
        }
        overlay?.onHistorySetup = { overlay?.showHistorySetup() }
        overlay?.onDefaultSelected = {
            val pkg = activePkg ?: foregroundPkg ?: ""
            selectedContact = null
            selectedHistoryEnvironment = null
            defaultContextSelected = true
            manualTitleOverrides.remove(pkg)
            lastSignature = ""
            val snapshot = currentSnapshot ?: run {
                val root = rootInActiveWindow
                val raw = root?.packageName?.toString()?.let { adapters[it]?.extract(root, resources) }
                raw?.also { currentSnapshot = it }
            }
            overlay?.showDefaultContext(snapshot?.title)
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        overlay?.onImportHistory = { environment -> importHistory(environment) }
        overlay?.onStopImportHistory = { stopHistoryImport() }
        overlay?.onDisable = {
            stopHistoryImport()
            prefs.enabled = false
            overlay?.hide()
            stopKeepAlive()
        }
        // 保持进程处于前台重要性，降低系统冻结服务的概率。
        if (prefs.enabled) runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // 部分系统可能杀死并重启服务。重新连接时主动恢复当前聊天的悬浮窗，
        // 不必等待用户再次滚动页面。
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
                foregroundPkg = fg
                // The master switch controls the overlay lifetime. Do not hide
                // it merely because the current app is not an adapted chat;
                // this is important when entering WeChat/QQ before their tree
                // has produced a readable snapshot.
                main.post { overlay?.showIdle(currentSnapshot?.title) }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        if (historyImporting || overlay?.blocksAutoAnalysis() == true) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg != null && activePkg != null && pkg != activePkg) {
            // A manual contact choice belongs to one foreground chat app.
            // Never carry a WeChat selection into another app's conversation.
            selectedContact = null
            defaultContextSelected = false
        }
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: return
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources) ?: return
        // Stabilize the title BEFORE anything below reads it: some apps (X) show
        // a transient "连接中…" title for a moment right after opening a thread.
        val detectedSnapshot = stabilizeTitle(pkg ?: "", rawSnapshot)
        lastObservedTitles[pkg ?: ""] = detectedSnapshot.title
        val snapshot = applyManualTitleOverride(pkg ?: "", detectedSnapshot)
        val identified = if (selectedContact != null && (selectedHistoryEnvironment == null || selectedHistoryEnvironment == pkg)) {
            snapshot.copy(title = selectedContact!!.name)
        } else snapshot
        overlay?.setConversationInfo(identified.title, identified.latestFrom == "me")
        // An empty snapshot is still useful diagnostic state. In particular,
        // WeChat can expose the chat shell but hide both title and message text;
        // applying the whitelist first made this look exactly like "nothing was
        // found" when a contact whitelist was configured.
        if (identified.messages.isEmpty()) {
            overlay?.setRecognitionInfo("无障碍节点：未读到消息文本", emptyList())
            if (!screenshotAllowed(pkg ?: "")) {
                overlay?.setNote(wechatDiagnostic(identified))
                main.post { overlay?.showIdle(identified.title, false) }
                return
            }
        }
        // A missing title is intentional for WeChat: the user may use the
        // configured default relationship or choose a contact manually. It
        // must not be rejected by a title whitelist before analysis starts.
        if (!defaultContextSelected && identified.title != null && !prefs.isAllowed(identified.title)) {
            main.post { overlay?.showIdle(identified.title, identified.latestFrom == "me") }
            return
        }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (identified.messages.isEmpty()) {
            if (!screenshotAllowed(pkg ?: "")) {
                overlay?.setNote(WECHAT_NO_SCREENSHOT_NOTE)
                main.post { overlay?.showIdle(identified.title, false) }
                return
            }
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val packageName = pkg ?: ""
                val sig = ocrSignature(packageName, identified.title, identified.bubbleRects)
                val repeatedEmptyTree = packageName == "com.tencent.mm" || packageName == "com.tencent.mobileqq"
                val now = SystemClock.elapsedRealtime()
                if (repeatedEmptyTree) {
                    // WeChat/QQ can keep the same bubble rectangles while the
                    // newest message text changes. Refresh at most once per
                    // second so the latest line is not lost to geometry dedupe.
                    if (now - lastEmptyTreeOcrRequestAt < 900L) return
                    lastEmptyTreeOcrRequestAt = now
                } else if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                lastOcrSignature = sig
                ocrCapture(identified.title, identified.bubbleRects, packageName, manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = identified
        overlay?.setRecognitionInfo("无障碍节点", identified.messages)
        // Save as soon as a new visible window is recognized. This keeps the
        // per-contact log current even when auto-analysis is disabled or the
        // same screen is later deduplicated.
        if (prefs.historyAutoUpdate && !defaultContextSelected) submit { runCatching {
            ContextBuilder.recordVisible(this, identified, pkg ?: "")
        } }
        if (!identified.atLatest) {
            // The user is looking at older messages. They are useful for
            // building the contact's local history, but must never be treated
            // as the latest turn that needs a reply.
            currentSnapshot = null
            overlay?.setNote("已保存当前旧消息；检测到仍可向下滚动，请回到聊天底部后再分析最新对话")
            main.post { overlay?.showIdle(identified.title, false) }
            return
        }
        val sig = identified.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // 内容未变但悬浮窗消失（可能被系统回收，或离开聊天后又返回）时，
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) {
            main.post { overlay?.showIdle(identified.title, identified.latestFrom == "me") }
            return
        }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        main.post {
            overlay?.resetForNewConversation()
            overlay?.setConversationInfo(identified.title, identified.latestFrom == "me")
        }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] hasTitle=${!identified.title.isNullOrBlank()} " +
            "titleLength=${identified.title?.length ?: 0} n=${identified.messages.size} " +
            identified.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Analyze every new message when auto-analyze is enabled. If the latest
        // line is ours, the reply route switches to continuation mode instead of
        // pretending that our own question came from the other person.
        if (!prefs.autoAnalyze) {
            main.post { overlay?.showIdle(identified.title, identified.latestFrom == "me") }
            return
        }

        pendingSnapshot = identified
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Replace a transient title with the last known-good one for this package
     *  (if any), and otherwise remember the current title as the new good one. */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    /** Keep a user correction across repeated accessibility events for this chat. */
    private fun applyManualTitleOverride(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        val override = manualTitleOverrides[pkg] ?: return snapshot
        val observed = snapshot.title?.trim()
        if (observed == override.sourceTitle ||
            (observed.isNullOrEmpty() && override.sourceTitle.isNullOrEmpty())
        ) return snapshot.copy(title = override.correctedTitle)
        manualTitleOverrides.remove(pkg)
        return snapshot
    }

    private fun runAnalysis() {
        if (historyImporting) return
        val snapshot = pendingSnapshot ?: return
        if (analyzing) return

        if (!prefs.hasKey()) { main.post { overlay?.showError("未设置判断接口密钥，去设置里填") }; return }
        analyzing = true
        main.post {
            overlay?.setConversationInfo(snapshot.title, snapshot.latestFrom == "me")
            overlay?.showLoading()
            overlay?.setNote(snapshot.note)
        }
        val client = JevClient(prefs)
        val rel = prefs.relationship
        val pkg = activePkg ?: ""
        // Knowledge context first (local file reads only, a few ms), then the two
        // network calls in parallel on the pool. A failure here must never stop
        // the analysis — it just means no extra context this round.
        submit {
            val ctx = try {
                ContextBuilder.build(this, snapshot, pkg, prefs, useContactHistory = !defaultContextSelected)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                overlay?.setContextInfo(
                    ctx?.notes?.size ?: 0,
                    ctx?.history?.size ?: 0,
                    if (ctx?.style?.isEmpty() == false) 1 else 0
                )
                overlay?.setHistoryInfo(ctx?.history ?: emptyList(), snapshot.messages)
            }

            val history = ctx?.history.orEmpty()
            val mergedMessages = (history.map { Msg(it.side, it.text) } + snapshot.messages)
                .takeLast(100)
            val analysisSnapshot = snapshot.copy(messages = mergedMessages)
            // History is now part of the same ordered message window. Keep the
            // contact/notes background, but do not send the same history twice.
            val analysisContext = ctx?.copy(history = emptyList())

            // Judgment is fast (~1s) — show it immediately.
            submit {
                val judgment = client.judge(analysisSnapshot, rel, analysisContext)
                main.post {
                    if (judgment.error != null) { analyzing = false; overlay?.showError(judgment.error) }
                    else overlay?.showJudgment(judgment)
                }
            }
            // Candidate replies are slower (generative + rank) — fill in when ready.
            submit {
                var replyError: String? = null
                    val ranked = try { client.draftAndRank(analysisSnapshot, rel, analysisContext) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                main.post {
                    analyzing = false
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Adapted apps reuse their bubble rectangles
     * and sender sides; other apps use the generic whole-screen OCR fallback.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // Always take a real screenshot for this explicit menu action. Adapted
        // apps can provide bubble bounds and sender sides to constrain OCR.
        val adapted = if (root != null) adapters[pkg]?.extract(root, resources) else null
        val detectedTitle = if (pkg == "com.tencent.mm") null else adapted?.title ?: root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        lastObservedTitles[pkg] = detectedTitle
        val title = applyManualTitleOverride(pkg, ChatSnapshot(detectedTitle, emptyList())).title
        overlay?.setConversationInfo(title, adapted?.latestFrom == "me")
        ocrCapture(title, adapted?.bubbleRects ?: emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /** WeChat is deliberately node-only: screenshot requests can trigger its
     * global anti-capture protection and make even manual screenshots fail. */
    private fun screenshotAllowed(pkg: String): Boolean = pkg != "com.tencent.mm"

    private fun wechatDiagnostic(snapshot: ChatSnapshot): String =
        "微信聊天页已进入；标题=${snapshot.title?.takeIf { it.isNotBlank() } ?: "未识别"}，消息节点=0。为避免再次触发微信风控，自动截屏已关闭；可用悬浮球长按菜单中的“截屏识别一次”主动验证。"

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (!manual && !screenshotAllowed(pkg)) return
        if (ocrBusy) return
        ocrBusy = true
        screenCapture.capture { res ->
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    // The tree and screenshot are separated by the transition
                    // and overlay-settle delay. Re-read the active app now so
                    // old list-page rectangles cannot be applied to the new
                    // chat frame.
                    val freshRoot = rootInActiveWindow
                    val freshSnapshot = freshRoot?.takeIf {
                        it.packageName?.toString() == pkg
                    }?.let { adapters[pkg]?.extract(it, resources) }
                    if (!manual && freshSnapshot == null) {
                        runCatching { res.bitmap.recycle() }
                        ocrBusy = false
                        lastOcrSignature = ""
                    } else if (freshSnapshot != null && freshSnapshot.messages.isNotEmpty()) {
                        runCatching { res.bitmap.recycle() }
                        val title = freshSnapshot.title?.takeIf { it.isNotBlank() } ?: treeTitle
                        finishOcrSnapshot(freshSnapshot.copy(title = title), pkg, manual)
                    } else {
                    val freshTitle = freshSnapshot?.title?.takeIf { it.isNotBlank() }
                    val effectiveTitle = if (manual) treeTitle ?: freshTitle else freshTitle ?: treeTitle
                    val effectiveRects = freshSnapshot?.bubbleRects?.takeIf { it.isNotEmpty() } ?: rects
                    // WeChat may hide the action-bar title from the node tree.
                    // Read the top bar locally before reading message text.
                    resolveOcrTitle(res.bitmap, effectiveTitle) { title ->
                    if (effectiveRects.isNotEmpty()) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = if (!manual) {
                            rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        } else null
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) effectiveRects else fresh, title, pkg, manual)
                    } else ocrWholeScreen(res.bitmap, title, pkg, manual)
                    }
                    }
                }
            }
        }
    }

    /** Resolve a hidden action-bar title from the screenshot without a network call. */
    private fun resolveOcrTitle(bmp: Bitmap, treeTitle: String?, cb: (String?) -> Unit) {
        val known = treeTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (known != null) { cb(known); return }

        val top = Rect(0, 0, bmp.width, (bmp.height * TITLE_CROP).toInt())
        ocr.recognize(bmp, top) { lines ->
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val best = lines.filter { line ->
                val text = line.text.trim()
                val b = line.bounds
                text.isNotEmpty() && text.length <= 24 && !looksLikeTimestampForTitle(text) &&
                    b.centerX() in (screenWidth * 0.20).toInt()..(screenWidth * 0.80).toInt() &&
                    b.bottom <= (screenHeight * 0.18).toInt()
            }.minByOrNull { line ->
                kotlin.math.abs(line.bounds.centerX() - screenWidth / 2) + line.bounds.top / 20
            }?.text?.trim()?.takeIf { it.isNotEmpty() }
            cb(best)
        }
    }

    private fun looksLikeTimestampForTitle(text: String): Boolean =
        Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(text) ||
            Regex("""\d+月\d+日""").containsMatchIn(text) ||
            text == "昨天" || text == "今天"

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(
        bmp: Bitmap,
        rects: List<BubbleRect>,
        title: String?,
        pkg: String,
        manual: Boolean
    ) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val note = listOfNotNull(OCR_NOTE, lastOcrGroupingNote).joinToString("\n")
            finishOcrSnapshot(ChatSnapshot(treeTitle, msgs, note = note), pkg, manual)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val groupCenters = ArrayList<Float>()
        val buf = StringBuilder()
        val groupLines = ArrayList<OcrLine>()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        var minCenter = Float.MAX_VALUE
        var maxCenter = -Float.MAX_VALUE
        fun flush() {
            if (buf.isEmpty()) return
            val center = if (groupLines.isEmpty()) 0f
            else groupLines.map { it.bounds.centerX() }.average().toFloat()
            minCenter = minOf(minCenter, center)
            maxCenter = maxOf(maxCenter, center)
            groupCenters.add(center)
            // A fixed midpoint is wrong for apps whose bubbles are inset or
            // whose screenshot window is narrower than displayMetrics. Infer
            // the split from the actual groups on this screen when both sides
            // are visible, while retaining the conventional right-half rule
            // for a genuinely single-sided screen.
            val side = if (center > screenWidth * 0.54f) "me" else "other"
            out.add(Msg(side, buf.toString()))
            buf.setLength(0)
            groupLines.clear()
        }
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    flush()
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            groupLines.add(l)
            prev = l
        }
        flush()
        val spread = maxCenter - minCenter
        if (out.isNotEmpty() && spread > screenWidth * 0.12f) {
            val split = (minCenter + maxCenter) / 2f
            val corrected = out.mapIndexed { index, msg ->
                val group = groupCenters.getOrNull(index) ?: split
                msg.copy(side = if (group > split) "me" else "other")
            }
            lastOcrGroupingNote = "OCR分边：左右中心 ${minCenter.roundToInt()} / ${maxCenter.roundToInt()}，分界 ${split.roundToInt()}"
            return corrected
        }
        lastOcrGroupingNote = if (out.isEmpty()) "OCR分边：没有有效文本"
        else "OCR分边：本屏只看到一侧（中心 ${minCenter.roundToInt()}），身份可能不可靠"
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean) {
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!defaultContextSelected && snapshot.title != null && !prefs.isAllowed(snapshot.title)) {
            overlay?.showIdle(snapshot.title, snapshot.latestFrom == "me")
            return
        }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        overlay?.setRecognitionInfo(if (manual) "手动 OCR" else "自动 OCR", snapshot.messages)
        if (prefs.historyAutoUpdate && !defaultContextSelected) submit { runCatching {
            ContextBuilder.recordVisible(this, snapshot, pkg)
        } }
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) {
                overlay?.showIdle(snapshot.title, snapshot.latestFrom == "me")
            }
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        overlay?.resetForNewConversation()
        overlay?.setConversationInfo(snapshot.title, snapshot.latestFrom == "me")
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title, snapshot.latestFrom == "me")
        }
    }

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submit {
            // Fast path: SET_TEXT works when the box already has input focus and no
            // IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (pops the keyboard) and retry SET_TEXT;
                // if the IME composing region still swallows it (WeChat), PASTE from
                // the clipboard. The box is cleared before PASTE so a SET_TEXT that
                // silently took (but failed verification) never gets doubled.
                // Never clicks send.
                val edit = rootInActiveWindow?.let { findEditable(it) }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(text)
                    if (!ok) {
                        copyToClipboard(text)
                        val focused = rootInActiveWindow?.let { findEditable(it) } ?: edit
                        setTextRaw(focused, "")
                        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        val after = readInput()
                        ok = (after != null && after.contains(text)) || (pasted && after == null)
                        Log.i(TAG, "fill: paste=$pasted readback=${after?.length ?: -1}")
                    }
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    /** Set text on the chat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return false
        if (!setTextRaw(edit, text)) return false
        // SET_TEXT can report success without filling an unfocused box; verify.
        // Read back through refresh() — the node cache can still hold the old
        // (empty) text right after the action, which made Feishu look like a
        // failure and triggered a second PASTE on top.
        Thread.sleep(150)
        val after = readInput()
        Log.i(TAG, "fill: setText readback=${after?.length ?: -1} want=${text.length}")
        return after == text
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Current text of the input box, fetched fresh (bypassing the node cache). */
    private fun readInput(): String? {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return null
        runCatching { edit.refresh() }
        return edit.text?.toString()
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(historyStep)
        main.removeCallbacks(debounce)
        historySession = null
        historyImporting = false
        super.onDestroy()
        runCatching { unregisterReceiver(enabledReceiver) }
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onContactSelected = null
        overlay?.onDefaultSelected = null
        overlay?.onListContacts = null
        overlay?.onDisable = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onImportHistory = null
        overlay?.onEnvironmentSelected = null
        overlay?.onHistorySetup = null
        overlay?.onStopImportHistory = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    private fun stopKeepAlive() {
        runCatching { stopService(Intent(this, KeepAliveService::class.java)) }
    }

    /** Import older visible windows into the selected contact without analysis. */
    private fun importHistory(pkg: String) {
        if (historyImporting) { overlay?.toast("历史预采集正在进行"); return }
        val contact = selectedContact
        if (contact == null) { overlay?.toast("请先选择知识库联系人"); return }
        if (pkg.isBlank() || adapters[pkg] == null) {
            overlay?.toast("当前应用暂不支持历史预采集"); return
        }
        main.removeCallbacks(debounce)
        main.removeCallbacks(historyStep)
        pendingSnapshot = null
        historyImporting = true
        activePkg = pkg
        selectedHistoryEnvironment = pkg
        val session = HistoryImportSession(contact, pkg)
        historySession = session
        updateHistoryProgress(session, "准备读取，请打开该联系人的${com.jev.probe.core.kb.ChatEnvironment.label(pkg)}聊天并回到最新消息")
        main.postDelayed(historyStep, HISTORY_IMPORT_DELAY_MS)
    }

    /** The floating window may be active; read the focused application beneath it. */
    private fun chatRoot(expectedPkg: String?): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && (expectedPkg == null || active.packageName?.toString() == expectedPkg)) return active
        return windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused) &&
                (expectedPkg == null || it.root?.packageName?.toString() == expectedPkg)
        }?.root
    }

    private fun updateHistoryProgress(session: HistoryImportSession, message: String, running: Boolean = true) {
        val entries = KbStore.get(this).allLog(session.contact.id, session.environment)
        overlay?.setHistoryImportProgress(session.contact, session.environment, entries,
            "$message\n已采集 ${session.pages} 页 · 新增 ${session.added} 条 · 重复 ${session.duplicates} 条\n本环境已保存 ${entries.size} 条", running)
    }

    private fun stepHistoryImport() {
        val session = historySession ?: return
        if (!historyImporting || !prefs.enabled) { stopHistoryImport(); return }
        try {
            val root = chatRoot(session.environment)
            val raw = root?.let { adapters[session.environment]?.extract(it, resources) }
            if (raw == null || raw.messages.isEmpty()) {
                val message = if (root == null) "等待打开所选聊天环境"
                    else if (raw == null) "等待进入该联系人的聊天页"
                    else "未读到消息节点，请检查无障碍权限或手动翻页"
                waitForHistoryPage(session, message)
                return
            }
            if (session.pages == 0 && !raw.atLatest) {
                waitForHistoryPage(session, "当前不在最新消息位置，请先回到聊天底部；确认后才开始预加载")
                return
            }
            val keys = raw.messages.map { it.side + "\u001f" + KbStore.normalizeText(it.text) }
            if (keys == session.lastKeys) {
                val scroll = findHistoryScrollNode(root)
                val moved = scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
                session.awaitingScroll = moved
                waitForHistoryPage(session, if (moved) "上一页仍在刷新，正在重试读取"
                    else "页面未变化，请手动向上翻到更早消息；保留部分重叠消息")
                return
            }
            val store = KbStore.get(this)
            val before = store.logSize(session.contact.id, session.environment)
            val entries = raw.messages.map {
                com.jev.probe.core.kb.LogEntry(it.side, it.text, 0L, session.environment)
            }
            val ok = if (session.pages == 0) store.appendLog(session.contact.id, entries)
                else store.prependLog(session.contact.id, entries)
            if (!ok) { finishHistoryImport("保存失败：历史未成功写入，请检查手机存储空间"); return }
            val added = (store.logSize(session.contact.id, session.environment) - before).coerceAtLeast(0)
            session.added += added
            session.duplicates += (entries.size - added).coerceAtLeast(0)
            session.pages++
            session.lastKeys = keys
            session.waitingSince = SystemClock.elapsedRealtime()
            overlay?.setRecognitionInfo("历史预加载 · 无障碍节点", raw.messages)
            if (session.pages >= HISTORY_IMPORT_MAX_PAGES || store.logSize(session.contact.id, session.environment) >= KbStore.MAX_LOG) {
                finishHistoryImport("本轮已完成，达到采集上限；请回到最新消息后分析")
                return
            }
            val scroll = findHistoryScrollNode(root)
            val moved = scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            session.awaitingScroll = moved
            updateHistoryProgress(session, if (moved) "已保存本页，正在向上读取更早消息"
                else "本页已保存，请手动向上翻页；到达最早记录后点击停止并保存")
            main.postDelayed(historyStep, HISTORY_IMPORT_DELAY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "history import failed: ${e.javaClass.simpleName}")
            finishHistoryImport("预加载出错：${e.javaClass.simpleName}，已成功保存的记录保留")
        }
    }

    private fun waitForHistoryPage(session: HistoryImportSession, message: String) {
        val elapsed = SystemClock.elapsedRealtime() - session.waitingSince
        if (elapsed >= HISTORY_IMPORT_WAIT_MS) {
            finishHistoryImport(if (session.pages == 0) "预加载未完成：两分钟内没有读到消息"
                else "等待超时，已结束本轮采集；已保存记录保留")
            return
        }
        session.awaitingScroll = false
        updateHistoryProgress(session, message)
        main.postDelayed(historyStep, HISTORY_IMPORT_DELAY_MS)
    }

    private fun stopHistoryImport() {
        if (!historyImporting) return
        finishHistoryImport("已停止，采集到的记录已保存；请回到最新消息后分析")
    }

    private fun finishHistoryImport(message: String) {
        val session = historySession ?: return
        historyImporting = false
        historySession = null
        main.removeCallbacks(historyStep)
        currentSnapshot = null
        pendingSnapshot = null
        lastSignature = ""
        updateHistoryProgress(session, message, running = false)
    }

    private fun findHistoryScrollNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 6000) {
            val node = stack.removeLast()
            val b = Rect(); node.getBoundsInScreen(b)
            if (node.isScrollable && b.height() > resources.displayMetrics.heightPixels * 0.25f &&
                b.centerY() in (resources.displayMetrics.heightPixels * 0.20f).toInt()..(resources.displayMetrics.heightPixels * 0.85f).toInt() &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
            ) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    companion object {
        private const val TAG = "JEVASSIST"
        const val ACTION_SET_ENABLED = "com.jev.probe.custom.SET_ENABLED"
        const val EXTRA_ENABLED = "enabled"
        const val ACTION_SETUP_HISTORY = "com.jev.probe.custom.SETUP_HISTORY"
        const val EXTRA_CONTACT_ID = "contact_id"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TITLE_CROP = 0.18f
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f
        private const val HISTORY_IMPORT_MAX_PAGES = 100
        private const val HISTORY_IMPORT_DELAY_MS = 1000L
        private const val HISTORY_IMPORT_WAIT_MS = 120_000L

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 按消息气泡左右位置推断我方/对方，复杂布局可能需要人工确认"
        private const val WECHAT_NO_SCREENSHOT_NOTE = "微信模式：已禁用截屏识别，避免触发微信风控；当前仅使用无障碍文字节点"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
