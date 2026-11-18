package com.example.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * سرویس دسترسی‌پذیری: «چشم و دست» دستیار.
 * صفحه را می‌خواند و کلیک/تایپ/اسکرول انجام می‌دهد. حلقه‌ی هوش مصنوعی هم داخل همین سرویس اجرا می‌شود
 * تا وقتی برنامه‌های دیگر باز هستند، قطع نشود.
 */
class AgentService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var instance: AgentService? = null
        @Volatile var logListener: ((String) -> Unit)? = null
        val history = CopyOnWriteArrayList<String>()
        private const val MAX_NODES = 150
    }

    private var tts: TextToSpeech? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val agent = Agent(this)
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        log("✅ سرویس فعال است. حالا حرف بزنید.")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("fa"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        tts?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------- کنترل از بیرون ----------

    fun submit(task: String) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val key = prefs.getString("key", "").orEmpty().trim()
        val model = prefs.getString("model", "").orEmpty().trim().ifBlank { "claude-sonnet-5-5" }
        if (key.isEmpty()) {
            log("⚠️ ابتدا کلید API را وارد و ذخیره کنید.")
            return
        }
        agent.cancelled = true
        executor.execute {
            try {
                agent.run(task, key, model)
            } catch (e: Exception) {
                agent.reset()
                log("❌ خطا: ${e.message}")
                speak("خطایی رخ داد")
            }
        }
    }

    fun cancel() {
        agent.cancelled = true
        tts?.stop()
    }

    fun log(line: String) {
        history.add(line)
        while (history.size > 200) history.removeAt(0)
        logListener?.invoke(line)
    }

    fun speak(text: String) {
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent")
    }

    // ---------- خواندن صفحه ----------

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(screen not available)"
        nodes.clear()
        val sb = StringBuilder("package: ${root.packageName}\n")
        walk(root, sb)
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || nodes.size >= MAX_NODES) return
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.take(100)
        val desc = n.contentDescription?.toString()?.take(100)
        val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() ||
            n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (interesting) {
            val i = nodes.size
            nodes.add(n)
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("click")
            if (n.isEditable) flags.add("edit")
            if (n.isScrollable) flags.add("scroll")
            if (n.isChecked) flags.add("checked")
            sb.append("[").append(i).append("] ")
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (!text.isNullOrBlank()) sb.append(" \"").append(text).append("\"")
            if (!desc.isNullOrBlank()) sb.append(" desc=\"").append(desc).append("\"")
            if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(")")
            sb.append('\n')
        }
        for (c in 0 until n.childCount) walk(n.getChild(c), sb)
    }

    // ---------- اکشن‌ها ----------

    fun tap(index: Int): String {
        val n = nodes.getOrNull(index) ?: return "error: invalid index"
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path()
        p.moveTo(r.exactCenterX(), r.exactCenterY())
        return if (gesture(p, 60)) "ok (gesture)" else "error: tap failed"
    }

    fun typeText(index: Int, text: String, submit: Boolean): String {
        val n = nodes.getOrNull(index) ?: return "error: invalid index"
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "error: could not set text (is this an editable field?)"
        }
        if (submit) {
            Thread.sleep(300)
            if (Build.VERSION.SDK_INT >= 30) {
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return "ok"
    }

    fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val p = Path()
        when (direction) {
            "down" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.25f) }
            "up" -> { p.moveTo(w / 2, h * 0.25f); p.lineTo(w / 2, h * 0.75f) }
            "right" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "left" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return "error: direction must be up/down/left/right"
        }
        return if (gesture(p, 350)) "ok" else "error: scroll failed"
    }

    fun press(button: String): String {
        val action = when (button) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "error: unknown button"
        }
        return if (performGlobalAction(action)) "ok" else "error: failed"
    }

    fun openApp(name: String): String {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labeled = pm.queryIntentActivities(q, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        var hit = labeled.firstOrNull { it.first.equals(name, ignoreCase = true) }
        if (hit == null) hit = labeled.firstOrNull { it.first.contains(name, ignoreCase = true) }
        if (hit == null) {
            val names = labeled.map { it.first }.sorted().take(60).joinToString(", ")
            return "error: app not found. Installed apps: $names"
        }
        val intent = pm.getLaunchIntentForPackage(hit.second) ?: return "error: cannot launch"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Thread.sleep(1200)
        return "ok: opened ${hit.first}"
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!dispatched) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }
}
