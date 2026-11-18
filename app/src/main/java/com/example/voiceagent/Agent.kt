package com.example.voiceagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * حلقه‌ی عامل: صفحه را به مدل Claude می‌دهد، مدل یک ابزار (tap/type/...) انتخاب می‌کند،
 * اجرا می‌شود، صفحه‌ی جدید برمی‌گردد و تا «done» ادامه پیدا می‌کند.
 */
class Agent(private val svc: AgentService) {

    @Volatile var cancelled = false

    private var messages = JSONArray()
    private var pendingResults: JSONArray? = null
    private var lastScreen: JSONObject? = null

    companion object {
        private const val MAX_STEPS = 30

        private const val SYSTEM = """You are a voice assistant that operates an Android phone for the user through accessibility tools.
The user speaks (usually Persian). Each turn you receive the current screen as a numbered list of UI elements: [index] Class "text" desc="..." (flags).
Rules:
- Do exactly ONE action per turn (indices change after every action).
- Prefer open_app to launch apps. Use scroll if the element you need is not visible. After typing in a search box use submit=true.
- If an action fails or the screen did not change, try a different approach instead of repeating it.
- Never complete payments, purchases, deletions, or enter passwords/codes without first calling done with a short question asking the user to confirm. Sending a message the user explicitly dictated is fine.
- When finished (or if impossible), call done with a short message in the user's language. The message is read aloud, so keep it brief and natural."""

        private fun prop(type: String, desc: String, options: List<String>? = null): JSONObject {
            val o = JSONObject().put("type", type).put("description", desc)
            if (options != null) o.put("enum", JSONArray(options))
            return o
        }

        private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject().put("name", name).put("description", desc).put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )

        private val TOOLS: JSONArray = JSONArray()
            .put(tool("tap", "Tap a UI element by its index.",
                JSONObject().put("index", prop("integer", "Element index from the screen list")), listOf("index")))
            .put(tool("type_text", "Type text into an editable element (replaces existing text).",
                JSONObject()
                    .put("index", prop("integer", "Index of the editable element"))
                    .put("text", prop("string", "Text to enter"))
                    .put("submit", prop("boolean", "Press the keyboard Enter/Search key after typing")),
                listOf("index", "text")))
            .put(tool("scroll", "Scroll the screen. 'down' reveals content further down the page.",
                JSONObject().put("direction", prop("string", "Direction", listOf("up", "down", "left", "right"))),
                listOf("direction")))
            .put(tool("press", "Press a system button.",
                JSONObject().put("button", prop("string", "Button", listOf("back", "home", "recents", "notifications", "quick_settings"))),
                listOf("button")))
            .put(tool("open_app", "Launch an installed app by its name (as shown under its icon).",
                JSONObject().put("name", prop("string", "App name")), listOf("name")))
            .put(tool("wait", "Wait for the screen to load.",
                JSONObject().put("seconds", prop("number", "Seconds to wait (0.5-10)")), listOf("seconds")))
            .put(tool("done", "Finish the task and speak a short message to the user.",
                JSONObject().put("message", prop("string", "Short message for the user")), listOf("message")))
    }

    fun reset() {
        messages = JSONArray()
        pendingResults = null
        lastScreen = null
    }

    fun run(task: String, apiKey: String, model: String) {
        cancelled = false
        svc.log("🗣 $task")
        if (messages.length() > 60) reset()

        val first = JSONArray()
        pendingResults?.let { for (i in 0 until it.length()) first.put(it.getJSONObject(i)) }
        pendingResults = null
        first.put(textBlock("User request: $task"))
        addScreen(first)
        messages.put(JSONObject().put("role", "user").put("content", first))

        for (step in 1..MAX_STEPS) {
            if (cancelled) {
                reset()
                svc.log("⏹ متوقف شد")
                return
            }
            val resp = callApi(apiKey, model)
            val blocks = resp.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", blocks))

            val toolBlocks = ArrayList<JSONObject>()
            val spoken = StringBuilder()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                when (b.getString("type")) {
                    "text" -> {
                        val t = b.getString("text").trim()
                        if (t.isNotEmpty()) { svc.log("🤖 $t"); spoken.append(t).append(' ') }
                    }
                    "tool_use" -> toolBlocks.add(b)
                }
            }

            if (toolBlocks.isEmpty()) {
                svc.speak(spoken.toString())
                return
            }

            val firstTool = toolBlocks[0]
            val name = firstTool.getString("name")
            val input = firstTool.optJSONObject("input") ?: JSONObject()

            if (name == "done") {
                val msg = input.optString("message")
                svc.log("✅ $msg")
                svc.speak(msg)
                val results = JSONArray()
                for (tb in toolBlocks) results.put(toolResult(tb.getString("id"), "ok"))
                pendingResults = results
                return
            }

            val out = try { exec(name, input) } catch (e: Exception) { "error: ${e.message}" }
            svc.log("⚙️ $name $input → $out")

            val content = JSONArray()
            for ((idx, tb) in toolBlocks.withIndex()) {
                content.put(toolResult(tb.getString("id"), if (idx == 0) out else "skipped: only one action per turn"))
            }
            Thread.sleep(1000)
            addScreen(content)
            messages.put(JSONObject().put("role", "user").put("content", content))
        }

        svc.log("⚠️ به حداکثر تعداد مراحل رسید.")
        svc.speak("نتوانستم کار را کامل کنم")
        reset()
    }

    private fun exec(name: String, inp: JSONObject): String = when (name) {
        "tap" -> svc.tap(inp.optInt("index", -1))
        "type_text" -> svc.typeText(inp.optInt("index", -1), inp.optString("text"), inp.optBoolean("submit", false))
        "scroll" -> svc.scroll(inp.optString("direction", "down"))
        "press" -> svc.press(inp.optString("button"))
        "open_app" -> svc.openApp(inp.optString("name"))
        "wait" -> {
            Thread.sleep((inp.optDouble("seconds", 1.0).coerceIn(0.5, 10.0) * 1000).toLong())
            "ok"
        }
        else -> "error: unknown tool"
    }

    private fun addScreen(content: JSONArray) {
        lastScreen?.put("text", "[old screen omitted]")
        val b = textBlock("Current screen:\n" + svc.dumpScreen())
        lastScreen = b
        content.put(b)
    }

    private fun textBlock(s: String) = JSONObject().put("type", "text").put("text", s)

    private fun toolResult(id: String, content: String) =
        JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", content)

    private fun callApi(apiKey: String, model: String): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM)
            .put("tools", TOOLS)
            .put("messages", messages)
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("API $code: ${text.take(300)}")
        return JSONObject(text)
    }
}
