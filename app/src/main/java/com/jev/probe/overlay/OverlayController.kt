package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.LogEntry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗：可拖动的小气泡，展开后显示聊天分析结果和 3 条排序候选回复。
 * 所有操作都只是复制或填入，不会自动发送。
 *
 * 设计目标：聊天页面保持可见，透明度可以调整；信息便于快速查看；
 * 气泡可以拖动、吸附到屏幕边缘并记住位置。
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: ConcentricBubbleView? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** Contact selected when automatic title extraction is wrong or missing. */
    var onContactSelected: ((Contact) -> Unit)? = null

    /** Select the one-off mode without any contact history. */
    var onDefaultSelected: (() -> Unit)? = null

    /** Supplies all contacts already stored in the local knowledge base. */
    var onListContacts: (() -> List<Contact>)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** Scroll upward and seed the selected contact's local history. */
    var onImportHistory: ((String) -> Unit)? = null
    var onEnvironmentSelected: ((String) -> Unit)? = null
    var onHistorySetup: (() -> Unit)? = null
    var onStopImportHistory: (() -> Unit)? = null

    /** Long-press menu action for turning the master switch off. */
    var onDisable: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0
    private var ctxStyle = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null
    private var recognitionInfo: String? = null
    private var historyInfo: String? = null
    private var historyExpanded = false

    /** Current title and sender mode, shown for on-device verification. */
    private var conversationTitle: String? = null
    private var continuationMode = false
    private var choosingContact = false
    private var selectedContact: Contact? = null
    private var selectedHistory: List<LogEntry> = emptyList()
    private var historyImportRunning = false
    private var selectedEnvironment: String? = null
    private var historyPanelVisible = false
    private var historyImportStatus: String? = null

    fun blocksAutoAnalysis(): Boolean = historyPanelVisible || choosingContact

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#DDE4EE"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(46))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46))
        }
        val b = ConcentricBubbleView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46))
        }
        wrap.addView(b)
        attachBubbleTouch(wrap, params)
        bubble = b
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(16, panelBg(), stroke = true)
            elevation = dp(10).toFloat()
            setPadding(dp(16), dp(13), dp(16), dp(13))
            layoutParams = FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "GalMagan 分析"; setTextColor(Color.parseColor("#172230")); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#667085")); textSize = 16f
        gravity = Gravity.CENTER
        minWidth = dp(40); minHeight = dp(40)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // 与屏幕两侧保留边距，避免悬浮窗贴边影响拖动。
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(54))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("预加载历史记录") { root?.removeView(menu); onHistorySetup?.invoke() })
        menu.addView(menuItem("把当前会话存为联系人") { onSaveContact?.invoke(); root?.removeView(menu) })
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("关闭 GalMagan") { root?.removeView(menu); onDisable?.invoke() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#172230")); textSize = 14f
        minHeight = dp(44)
        setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent(ctx, com.jev.probe.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
            bubble?.innerFilled = true
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
            bubble?.innerFilled = false
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?, continuation: Boolean = false) {
        if (blocksAutoAnalysis()) return
        setConversationInfo(title, continuation)
        ensureRoot(); bubble?.alpha = 0.55f
        // Either there is genuinely nothing to show yet, or the panel is empty
        // for some other reason (root got rebuilt after hide(), leaving
        // contentBox with zero children while lastJudgment still points at a
        // stale conversation) — either way an empty panel must never stay
        // literally blank.
        if (lastJudgment == null || contentBox?.childCount == 0) {
            setContent(captureInfoViews() + listOf(bigButton("分析当前对话") { onManualAnalyze?.invoke() }))
        }
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        choosingContact = false
        recognitionInfo = null
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        conversationTitle = null
        continuationMode = false
        historyInfo = null
        historyExpanded = false
        contentBox?.removeAllViews()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#2F6BFF"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    fun showLoading(status: String = "分析中…") {
        historyPanelVisible = false
        choosingContact = false
        lastJudgment = null
        lastFill = null
        ensureRoot(); bubble?.alpha = 1f
        resetBubbleColor()
        ctxNotes = 0; ctxHistory = 0; ctxStyle = 0 // counts for the round that is starting
        historyInfo = null
        historyExpanded = false
        replyError = null              // this round has not failed (yet)
        setContent(captureInfoViews() + listOf(hint(status)))
        if (!expanded) toggle()
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int, style: Int = 0) {
        ctxNotes = notes; ctxHistory = history; ctxStyle = style
    }

    /** Exact message list sent to the analysis clients. */
    fun setHistoryInfo(
        messages: List<com.jev.probe.core.Msg>,
        historyCount: Int,
        currentCount: Int
    ) {
        historyInfo = if (messages.isEmpty()) {
            "本次分析实际上下文：0 条（本地历史 0 条，当前识别 0 条）"
        } else buildString {
            append("本次分析实际上下文：").append(messages.size)
                .append(" 条（本地历史 ").append(historyCount)
                .append(" 条，当前识别 ").append(currentCount).append(" 条）\n")
            messages.forEachIndexed { index, item ->
                append(index + 1).append(". ")
                    .append(if (item.side == "me") "我：" else "对方：")
                    .append(item.text).append('\n')
            }
        }.trim()
        historyExpanded = false
    }

    /** Show the selected contact's local history before any analysis starts. */
    fun showContactHistory(contact: Contact, history: List<LogEntry>, environment: String) {
        selectedContact = contact
        selectedHistory = history
        historyImportRunning = false
        selectedEnvironment = environment
        historyPanelVisible = true
        historyImportStatus = null
        choosingContact = false
        setConversationInfo(contact.name, false)
        ensureRoot()
        setContent(captureInfoViews() + contactHistoryViews())
        if (!expanded) toggle()
    }

    /** Show the one-off analysis mode without binding this chat to a contact. */
    fun showDefaultContext(title: String?) {
        historyPanelVisible = false
        selectedEnvironment = null
        selectedContact = null
        selectedHistory = emptyList()
        historyImportRunning = false
        choosingContact = false
        setConversationInfo(title, false)
        ensureRoot()
        setContent(captureInfoViews() + defaultContextViews())
        if (!expanded) toggle()
    }

    fun showHistorySetup() {
        choosingContact = true
        historyPanelVisible = true
        ensureRoot()
        setContent(captureInfoViews())
        if (!expanded) toggle()
    }

    fun showEnvironmentChooser(contact: Contact) {
        selectedContact = contact
        choosingContact = false
        historyPanelVisible = true
        historyImportStatus = null
        setConversationInfo(contact.name, false)
        ensureRoot()
        val views = arrayListOf<View>(hint("${contact.name} · 选择聊天环境"))
        com.jev.probe.core.kb.ChatEnvironment.supported.forEach { environment ->
            views.add(bigButton(com.jev.probe.core.kb.ChatEnvironment.label(environment)) {
                onEnvironmentSelected?.invoke(environment)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
        }
        views.add(smallAction("重新选择联系人") { showHistorySetup() })
        setContent(views)
        if (!expanded) toggle()
    }

    fun setHistoryImportProgress(contact: Contact, environment: String, history: List<LogEntry>, status: String, running: Boolean) {
        selectedContact = contact
        selectedHistory = history
        historyImportRunning = running
        selectedEnvironment = environment
        historyPanelVisible = true
        choosingContact = false
        historyImportStatus = status
        setConversationInfo(contact.name, false)
        ensureRoot()
        setContent(captureInfoViews() + contactHistoryViews())
        if (!expanded) toggle()
    }

    private fun contactHistoryViews(): List<View> {
        val c = selectedContact ?: return emptyList()
        val latest = selectedHistory.lastOrNull()
        val views = ArrayList<View>()
        views.add(hint("联系人：${c.name}"))
        views.add(hint("聊天环境：${com.jev.probe.core.kb.ChatEnvironment.label(selectedEnvironment.orEmpty())}"))
        views.add(hint("本地已记录：${selectedHistory.size} 条"))
        views.add(hint(if (latest == null) "最新一条：暂无记录"
            else "最新一条：${if (latest.side == "me") "我" else "对方"}：${latest.text}"))
        historyImportStatus?.let { views.add(line(it, "#344054", 13f, bold = true)) }
        if (historyImportRunning) {
            views.add(smallAction("停止并保存") { onStopImportHistory?.invoke() })
        } else {
            views.add(hint("请在所选软件打开该联系人的聊天，先回到最新消息，再开始预加载。"))
            views.add(smallAction("开始预加载历史记录") { selectedEnvironment?.let { onImportHistory?.invoke(it) } })
            views.add(smallAction("更换聊天环境") { showEnvironmentChooser(c) })
            views.add(bigButton("分析当前对话") { onManualAnalyze?.invoke() })
        }
        if (!historyImportRunning) views.add(smallAction("重新选择联系人") { showHistorySetup() })
        return views
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /** Show what the capture layer actually recognized for on-device checking. */
    fun setRecognitionInfo(source: String, messages: List<com.jev.probe.core.Msg>) {
        val mine = messages.count { it.side == "me" }
        val other = messages.count { it.side == "other" }
        val preview = messages.takeLast(5).joinToString(" | ") {
            "${if (it.side == "me") "我" else "对方"}：${it.text.take(28)}"
        }
        recognitionInfo = if (preview.isBlank()) {
            "$source：共 ${messages.size} 条（我 $mine / 对方 $other）"
        } else {
            "$source：共 ${messages.size} 条（我 $mine / 对方 $other）\n$preview"
        }
    }

    /** Current extracted title and whether the latest message was sent by me. */
    fun setConversationInfo(title: String?, continuation: Boolean) {
        conversationTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: "默认对象"
        continuationMode = continuation
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun showError(msg: String) {
        historyPanelVisible = false
        choosingContact = false
        ensureRoot(); bubble?.alpha = 1f
        resetBubbleColor()
        val views = captureInfoViews().toMutableList()
        views.add(line("出错了", "#DC2626", 14f, true))
        views.add(hint(msg))
        views.add(bigButton("重新分析当前对话") { onManualAnalyze?.invoke() })
        setContent(views)
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        choosingContact = false
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; expanded = false
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        // Each analysis starts from the neutral color. Continuation analyses
        // have no danger score and must not inherit the previous warning color.
        resetBubbleColor()
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        views.addAll(captureInfoViews())

        // What context this read was based on (knowledge base / remembered history).
        views.add(hint(
            if (ctxNotes == 0 && ctxHistory == 0 && ctxStyle == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条 · 风格档案 $ctxStyle 份"))

        // How this snapshot was captured, when it changes how to read it.
        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }
        recognitionInfo?.let { if (it.isNotBlank()) views.add(hint(it)) }
        historyInfo?.let { info ->
            if (info.isNotBlank()) {
                val summary = info.substringBefore('\n')
                views.add(hint(summary))
                views.add(smallAction(
                    if (historyExpanded) "收起当前注入上下文" else "查看当前注入上下文"
                ) {
                    historyExpanded = !historyExpanded
                    lastJudgment?.let { render(it, generating) }
                })
                if (historyExpanded) {
                    val detail = info.substringAfter('\n', "")
                    if (detail.isNotBlank()) views.add(hint(detail))
                }
            }
        }

        if (a.continuation) {
            views.add(hint("最近一条是我发出的；这里只生成接着当前话题的下一句"))
        } else {
            a.dangerLevel?.let {
                val lvl = it.score.roundToInt()
                views.add(dangerBadge(lvl, it.maxLevel))
                tintBubbleDanger(it.score)
            }
            a.trueIntent?.let {
                views.add(line("对方真实意图：${INTENT[it.choice] ?: it.choice}", "#111827", 15f, true))
                views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
            }
            val bits = ArrayList<String>()
            a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
            a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
            a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
            if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
            a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }
        }

        views.add(divider())
        views.add(line(if (a.continuation) "接着当前话题" else "候选回复（模型排序）", "#9CA3AF", 12f))
        if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isEmpty()) {
                val msg = replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#EEF4FF") else Color.parseColor("#F5F7FA")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${pct}%"; setTextColor(Color.parseColor("#2F6BFF")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#172230")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        btns.addView(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        c.addView(btns)
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#2F6BFF"))
        background = card(18, if (primary) Color.parseColor("#2F6BFF") else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#667085"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        bubble?.accentColor = color
    }

    private fun resetBubbleColor() {
        bubble?.accentColor = DEFAULT_BUBBLE_COLOR
    }

    /** Small text-free overlay icon: two rings, with a filled inner ring when active. */
    private class ConcentricBubbleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        var innerFilled = false
            set(value) { field = value; invalidate() }
        var accentColor = Color.rgb(58, 122, 254)
            set(value) { field = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val center = width / 2f
            val outer = width * 0.40f
            val inner = width * 0.30f
            paint.color = accentColor
            paint.strokeWidth = width * 0.065f
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(center, center, outer, paint)
            if (innerFilled) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(center, center, inner, paint)
            } else {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(center, center, inner, paint)
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun captureInfoViews(): List<View> {
        val title = conversationTitle
        val titleView = line(
            "会话标题：${title ?: "未识别"}",
            if (title == null) "#C24141" else "#344054",
            12f
        ).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        if (historyImportRunning) return listOf(titleView)
        val titleControls = listOf(titleView, smallAction("选择联系人") {
            choosingContact = true
            setContent(captureInfoViews())
        })
        val info = ArrayList<View>(if (choosingContact) contactChooser() else titleControls)
        if (continuationMode) info.add(hint("模式：接着我刚发出的话题"))
        return info
    }

    private fun contactChooser(): List<View> {
        val contacts = onListContacts?.invoke().orEmpty().sortedBy { it.name.lowercase() }
        val views = ArrayList<View>(contacts.size + 2)
        views.add(hint("选择知识库联系人，或使用默认模式"))
        views.add(defaultChoice())
        contacts.forEach { contact ->
            views.add(contactChoice(contact))
        }
        if (contacts.isEmpty()) views.add(hint("知识库中还没有联系人"))
        views.add(smallAction("取消") {
            choosingContact = false; historyPanelVisible = false
            setContent(captureInfoViews() + listOf(bigButton("分析当前对话") { onManualAnalyze?.invoke() }))
        })
        return views
    }

    private fun defaultChoice() = TextView(ctx).apply {
        text = "默认（不使用联系人历史）"
        textSize = 13f
        setTextColor(Color.parseColor("#172230"))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = card(8, Color.WHITE, stroke = true)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) }
        setOnClickListener { onDefaultSelected?.invoke() }
    }

    private fun defaultContextViews(): List<View> = listOf(
        hint("默认模式：不使用联系人历史"),
        hint("本次分析只使用当前识别到的对话和全局设置，不读取或保存联系人历史"),
        bigButton("分析当前对话") { onManualAnalyze?.invoke() }
    )

    private fun contactChoice(contact: Contact) = TextView(ctx).apply {
        text = if (contact.aliases.isEmpty()) contact.name
        else "${contact.name}（${contact.aliases.joinToString("、")}）"
        textSize = 13f
        setTextColor(Color.parseColor("#172230"))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = card(8, Color.WHITE, stroke = true)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) }
        setOnClickListener {
            choosingContact = false
            onContactSelected?.invoke(contact)
        }
    }

    private fun smallAction(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#2F6BFF"))
        gravity = Gravity.CENTER
        background = card(9, Color.parseColor("#EEF4FF"))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            topMargin = dp(4)
        }
        setOnClickListener { onClick() }
    }

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#667085", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#DDE4EE"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val DEFAULT_BUBBLE_COLOR = Color.rgb(58, 122, 254)
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
