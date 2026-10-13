package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Note
import com.jev.probe.core.kb.timeLabel
import com.jev.probe.core.Prefs
import com.jev.probe.jev.ReplyClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Knowledge base manager: the notes the assistant may quote, and the contacts
 * that tie a conversation title (across apps) to a person.
 *
 * Everything shown here lives in the app-private `filesDir/kb` directory and
 * nowhere else. Plain code-built views, same card/pill vocabulary as
 * [SettingsActivity].
 */
class KnowledgeActivity : AppCompatActivity() {

    private lateinit var store: KbStore
    private lateinit var container: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()

    /** 0 = notes, 1 = contacts. */
    private var tab = 0

    private val accent = Color.parseColor("#2F6BFF")
    private val violet = Color.parseColor("#B45AD6")
    private val ink = Color.parseColor("#172230")
    private val sub = Color.parseColor("#667085")
    private val pillOff = Color.parseColor("#EEF2F7")
    private val red = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KbStore.get(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F6F8FB"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    // --------------------------------------------------------------- screens

    private fun render() {
        container.removeAllViews()
        container.addView(text("知识库与联系人", 24f, ink, bold = true))
        container.addView(text("只存在本机，不上传。分析时按会话标题匹配联系人、按关键词命中笔记。",
            12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) })
        container.addView(tabs())
        container.addView(styleSummary())
        if (tab == 0) renderNotes() else renderContacts()
    }

    private fun styleSummary(): View {
        val profile = store.styleProfile(null)
        val c = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        copy.addView(text("全局说话风格", 15f, ink, bold = true))
        copy.addView(text(
            if (profile == null) "尚未生成，候选回复将使用默认表达"
            else "已生成本地档案，用于贴近你的表达方式",
            12f, sub
        ).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(copy)
        header.addView(text(if (profile == null) "未生成" else "已就绪", 12f,
            if (profile == null) sub else violet, bold = true))
        c.addView(header)
        c.addView(twoButtons("查看档案", { styleDialog(null) },
            "合成 / 更新", { synthesizeStyle(null) }))
        return c
    }

    private fun tabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        listOf("笔记", "联系人").forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(40), 1f)
                setTextColor(if (i == tab) Color.WHITE else sub)
                setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                background = round(dp(10), if (i == tab) accent else Color.TRANSPARENT)
                setOnClickListener { tab = i; render() }
            }
            row.addView(pill)
        }
        row.background = round(dp(11), pillOff, stroke = true)
        row.setPadding(dp(3), dp(3), dp(3), dp(3))
        return row
    }

    // ----------------------------------------------------------------- notes

    private fun renderNotes() {
        val notes = store.notes().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建笔记", { editNoteDialog(null) },
            "从文本导入", { importNotesDialog() }))
        if (notes.isEmpty()) {
            container.addView(emptyCard("还没有笔记。写点该记住的事实：习惯、忌口、项目代号、约定过的时间。"))
            return
        }
        notes.forEach { container.addView(noteRow(it)) }
        container.addView(text("点条目编辑，长按删除。命中规则：任一标签或标题出现在会话标题或最近 6 条消息里。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun noteRow(n: Note): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = n.title.ifBlank { "（无标题）" } + if (n.alwaysOn) "  · 常驻" else ""
        left.addView(text(head, 15f, ink, bold = true))
        left.addView(text(
            if (n.tags.isEmpty()) "无标签" else "标签：" + n.tags.joinToString("、"),
            12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        left.addView(text(n.content.replace("\n", " ").take(46), 12f, sub)
            .apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(smallToggle(n.enabled) {
            store.saveNote(n.copy(enabled = !n.enabled)); render()
        })
        c.addView(row)
        c.setOnClickListener { editNoteDialog(n) }
        c.setOnLongClickListener {
            confirm("删除笔记", "删除「${n.title}」？不可恢复。") {
                store.deleteNote(n.id); render()
            }
            true
        }
        return c
    }

    private fun editNoteDialog(existing: Note?) {
        val box = dialogBox()
        val titleEdit = edit(existing?.title ?: "", "标题，例如：口味忌口")
        val contentEdit = edit(existing?.content ?: "", "正文，写清楚事实本身").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        }
        val tagsEdit = edit(existing?.tags?.joinToString("，") ?: "", "逗号分隔，例如：吃饭，周末")
        val alwaysRow = toggleRow("常驻（每次分析都带上）", existing?.alwaysOn ?: false)
        val enabledRow = toggleRow("启用", existing?.enabled ?: true)
        box.addView(label("标题")); box.addView(titleEdit)
        box.addView(label("正文")); box.addView(contentEdit)
        box.addView(label("标签")); box.addView(tagsEdit)
        box.addView(alwaysRow); box.addView(enabledRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建笔记" else "编辑笔记")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isBlank() && content.isBlank()) {
                    toast("标题和正文不能都空着"); return@setPositiveButton
                }
                store.saveNote(Note(
                    id = existing?.id ?: KbStore.newId(),
                    title = title,
                    content = content,
                    tags = splitTags(tagsEdit.text.toString()),
                    alwaysOn = (alwaysRow.tag as? Boolean) ?: false,
                    enabled = (enabledRow.tag as? Boolean) ?: true
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun importNotesDialog() {
        val box = dialogBox()
        box.addView(text("按空行分段，每段第一行当标题，其余当正文。", 12f, sub))
        val input = edit("", "口味忌口\n不吃香菜，海鲜过敏\n\n项目代号\n内部叫小蓝").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; gravity = Gravity.TOP
        }
        box.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle("从文本导入")
            .setView(wrapScroll(box))
            .setPositiveButton("导入") { _, _ ->
                val chunks = input.text.toString().split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
                var n = 0
                chunks.forEach { chunk ->
                    val lines = chunk.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        store.saveNote(Note(
                            id = KbStore.newId(),
                            title = lines.first(),
                            content = lines.drop(1).joinToString("\n")
                        ))
                        n++
                    }
                }
                toast(if (n == 0) "没解析出内容" else "已导入 $n 条")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    // -------------------------------------------------------------- contacts

    private fun renderContacts() {
        val contacts = store.contacts().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建联系人", { editContactDialog(null) }, null, null))
        if (contacts.isEmpty()) {
            container.addView(emptyCard(
                "还没有联系人。也可以在聊天里长按悬浮球，选「把当前会话存为联系人」。"))
            return
        }
        contacts.forEach { container.addView(contactRow(it)) }
        container.addView(text("点条目编辑，长按删除。会话标题等于名字或任一别名即算命中（忽略大小写与群人数后缀）。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun contactRow(c0: Contact): View {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(contactMark(c0.name))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        identity.addView(text(c0.name.ifBlank { "（无名）" }, 15f, ink, bold = true))
        if (c0.aliases.isNotEmpty()) {
            identity.addView(text("别名：" + c0.aliases.joinToString("、"), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        }
        top.addView(identity)
        val envCounts = store.historyEnvironments(c0.id).map { env ->
            "${appLabel(env)} ${store.logSize(c0.id, env)}"
        }
        top.addView(text(
            if (envCounts.isEmpty()) "历史 0 条" else envCounts.joinToString(" · "),
            11.5f, violet, bold = true
        ))
        c.addView(top)
        if (c0.apps.isNotEmpty())
            c.addView(text("来源：" + c0.apps.joinToString("、") { appLabel(it) }, 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.relationship.isNotBlank())
            c.addView(text("关系：" + c0.relationship.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.notes.isNotBlank())
            c.addView(text("备注：" + c0.notes.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })

        val logN = store.logSize(c0.id)
        val clear = TextView(this).apply {
            text = "清空此人历史（$logN 条）"
            textSize = 12.5f; setTextColor(red); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener {
                if (logN == 0) { toast("本来就没有历史"); return@setOnClickListener }
                confirm("清空历史", "删掉「${c0.name}」的 $logN 条聊天历史？联系人档案保留。") {
                    store.clearLog(c0.id); render()
                }
            }
        }
        c.addView(clear)
        val inspect = TextView(this).apply {
            text = "查看已记录历史（$logN 条）"
            textSize = 12.5f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { chooseHistoryEnvironment(c0) }
        }
        c.addView(inspect)
        val styleInspect = TextView(this).apply {
            text = "查看面向此人说话风格"
            textSize = 12.5f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { styleDialog(c0) }
        }
        c.addView(styleInspect)
        c.addView(smallAction("主动合成/更新面向此人风格") { synthesizeStyle(c0) })
        c.setOnClickListener { editContactDialog(c0) }
        c.setOnLongClickListener {
            confirm("删除联系人", "删除「${c0.name}」及其全部历史？不可恢复。") {
                store.deleteContact(c0.id); render()
            }
            true
        }
        return c
    }

    private fun styleDialog(contact: Contact?) {
        val profile = store.styleProfile(contact?.id)
        val title = contact?.name?.let { "面向$it · 说话风格" } ?: "全局说话风格"
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(dp(12), Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        body.addView(text(
            if (profile == null) "还没有合成风格档案。请先点击主动合成，并确保本地已有我方聊天历史。"
            else "已合成一份本地风格档案，只用于候选回复的表达参考，不会改变风险判断。",
            12f, sub
        ).apply { setPadding(0, 0, 0, dp(8)) })
        profile?.summary?.let { summary ->
            body.addView(text(summary, 14f, ink).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("编辑") { _, _ -> editStyleDialog(contact) }
            .setNegativeButton("关闭", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun editStyleDialog(contact: Contact?) {
        val profile = store.styleProfile(contact?.id)
        val box = dialogBox()
        box.addView(text(
            "这里保存的是你面向${contact?.name ?: "所有对象"}的表达风格描述，不是对方的性格。",
            12f, sub
        ))
        val input = edit(profile?.summary ?: "", "例如：语气直接但不生硬，句子较短，常先回应重点再补充细节").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 7
            gravity = Gravity.TOP
        }
        box.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (contact == null) "编辑全局说话风格" else "编辑面向${contact.name}的说话风格")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val summary = input.text.toString().trim()
                if (summary.isBlank()) {
                    toast("风格描述不能空；需要删除请使用清空风格档案")
                    return@setPositiveButton
                }
                store.saveStyleProfile(
                    contact?.id,
                    com.jev.probe.core.kb.StyleProfile(
                        if (contact == null) "全局说话风格" else "面向${contact.name}的说话风格",
                        summary
                    )
                )
                render()
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun styleDialogWindow(dialog: AlertDialog) {
        // Keep the title, content and action row on one solid surface. A
        // transparent window made the title float over the dimmed page.
        dialog.window?.setBackgroundDrawable(round(dp(18), Color.WHITE))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(sub)
    }

    private fun synthesizeStyle(contact: Contact?) {
        val messages = store.outgoingMessages(contact?.id) + store.legacyStyleExamples(contact?.id)
        if (messages.isEmpty()) {
            toast("没有可用于合成的我方聊天历史")
            return
        }
        toast("正在合成说话风格…")
        worker.execute {
            val old = store.styleProfile(contact?.id)?.summary.orEmpty()
            val result = runCatching { ReplyClient(Prefs(this)).synthesizeStyle(messages, old) }
                .getOrDefault("")
            val ok = result.isNotBlank() && store.saveStyleProfile(
                contact?.id,
                com.jev.probe.core.kb.StyleProfile(
                    if (contact == null) "全局说话风格" else "${contact.name}的说话风格",
                    result
                )
            )
            runOnUiThread {
                toast(if (ok) "说话风格已合成并保存" else "风格合成失败，请检查回复接口配置")
                if (ok) render()
            }
        }
    }

    private fun chooseHistoryEnvironment(contact: Contact) {
        val environments = store.historyEnvironments(contact.id)
        if (environments.isEmpty()) {
            historyDialog(contact, null)
            return
        }
        val labels = environments.map { env ->
            "${appLabel(env)}（${store.logSize(contact.id, env)} 条）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择聊天环境")
            .setItems(labels) { _, which -> historyDialog(contact, environments[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun historyDialog(contact: Contact, environment: String?) {
        val entries = store.allLog(contact.id, environment)
        val seen = HashSet<String>()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        if (entries.isEmpty()) {
            body.addView(text("这个联系人还没有本地历史记录。", 13f, sub))
        } else {
            body.addView(text("共 ${entries.size} 条，按保存顺序显示。相同发送方和文本再次出现时标记为可能重复。",
                12f, sub).apply { setPadding(0, 0, 0, dp(8)) })
            entries.forEachIndexed { index, entry ->
                val key = entry.side + "\u001f" + entry.text
                val duplicate = !seen.add(key)
                body.addView(historyBubble(index + 1, entry, duplicate))
            }
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(520)
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${contact.name} · ${if (environment == null) "本地历史" else appLabel(environment)}")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun historyBubble(index: Int, entry: com.jev.probe.core.kb.LogEntry, duplicate: Boolean): View {
        val mine = entry.side == "me"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(dp(12), if (mine) Color.parseColor("#EEF4FF") else Color.WHITE, stroke = true)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.90f).apply {
                marginStart = if (mine) dp(28) else 0
                marginEnd = if (mine) 0 else dp(28)
            }
        }
        val meta = buildString {
            append(index).append(" · ").append(if (mine) "我" else "对方")
            append(" · ").append(appLabel(entry.app))
            if (entry.ts > 0L) append(" · ").append(entry.timeLabel())
        }
        bubble.addView(text(meta, 10.5f, if (duplicate) red else sub, bold = duplicate))
        bubble.addView(text(entry.text, 13.5f, ink).apply {
            setPadding(0, dp(4), 0, if (duplicate) dp(3) else 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        if (duplicate) bubble.addView(text("可能重复", 10.5f, red, bold = true))
        row.addView(bubble)
        return row
    }

    private fun editContactDialog(existing: Contact?) {
        val box = dialogBox()
        val nameEdit = edit(existing?.name ?: "", "名字，一般就是会话标题")
        val aliasEdit = edit(existing?.aliases?.joinToString("\n") ?: "", "每行一个，例如另一个 App 里的昵称").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        val relEdit = edit(existing?.relationship ?: "", "例如：同事，带我做项目的组长")
        val notesEdit = edit(existing?.notes ?: "", "关于这个人要记住的事").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        box.addView(label("名字")); box.addView(nameEdit)
        box.addView(label("别名（每行一个）")); box.addView(aliasEdit)
        box.addView(label("关系")); box.addView(relEdit)
        box.addView(label("备注")); box.addView(notesEdit)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建联系人" else "编辑联系人")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("名字不能空"); return@setPositiveButton }
                store.saveContact(Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = name,
                    aliases = aliasEdit.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    apps = existing?.apps ?: emptyList(),
                    relationship = relEdit.text.toString().trim(),
                    notes = notesEdit.text.toString().trim(),
                    autoSummary = existing?.autoSummary ?: ""
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialogWindow(dialog)
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        "com.xingin.xhs" -> "小红书"
        else -> pkg
    }

    // ----------------------------------------------------------------- atoms

    private fun contactMark(name: String): TextView = TextView(this).apply {
        text = name.trim().firstOrNull()?.uppercase() ?: "?"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = round(dp(12), Color.parseColor("#496DFF"))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) }
    }

    private fun smallAction(labelText: String, onClick: () -> Unit) = TextView(this).apply {
        text = labelText
        textSize = 12.5f
        setTextColor(accent)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = round(dp(10), Color.parseColor("#EEF4FF"))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun wrapScroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun twoButtons(
        a: String, onA: () -> Unit,
        b: String?, onB: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        row.addView(wideBtn(a, true, onA))
        if (b != null && onB != null) row.addView(wideBtn(b, false, onB))
        return row
    }

    private fun wideBtn(labelText: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else accent)
        background = round(dp(11), if (primary) accent else Color.WHITE, stroke = !primary)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallToggle(on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = SwitchMaterial(this).apply {
            isChecked = initial
            contentDescription = labelText
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(40))
        }
        sw.setOnCheckedChangeListener { _, checked -> row.tag = checked }
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun emptyCard(msg: String): View = card().apply { addView(text(msg, 13f, sub)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(9), Color.parseColor("#F2F5F9"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#E1E7EF"))
    }
}
