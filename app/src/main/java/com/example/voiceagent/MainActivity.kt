package com.example.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var textInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (14 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        statusView = TextView(this)
        statusView.textSize = 15f

        keyInput = EditText(this)
        keyInput.hint = "کلید API (sk-ant-...)"
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.setText(prefs.getString("key", ""))

        modelInput = EditText(this)
        modelInput.hint = "نام مدل"
        modelInput.setText(prefs.getString("model", "claude-sonnet-5-5"))

        textInput = EditText(this)
        textInput.hint = "یا دستور را تایپ کنید"

        val micBtn = makeButton("🎤  صحبت کنید") { startListening() }
        micBtn.textSize = 20f

        logView = TextView(this)
        logView.textSize = 14f
        logScroll = ScrollView(this)
        logScroll.addView(logView)

        root.addView(statusView)
        root.addView(makeButton("فعال‌سازی سرویس دسترسی‌پذیری") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(keyInput)
        root.addView(modelInput)
        root.addView(makeButton("ذخیره تنظیمات") { savePrefs(); toast("ذخیره شد") })
        root.addView(micBtn)
        root.addView(textInput)
        root.addView(makeButton("ارسال دستور تایپی") {
            val t = textInput.text.toString().trim()
            if (t.isNotEmpty()) { textInput.setText(""); send(t) }
        })
        root.addView(makeButton("⏹ توقف") { AgentService.instance?.cancel() })
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        logView.text = AgentService.history.joinToString("\n") + "\n"
        AgentService.logListener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onPause() {
        AgentService.logListener = null
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun refreshStatus() {
        statusView.text = if (AgentService.instance != null)
            "✅ سرویس دسترسی‌پذیری فعال است"
        else
            "⚠️ سرویس فعال نیست — دکمه‌ی زیر را بزنید و «دستیار صوتی» را روشن کنید"
    }

    private fun savePrefs() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("key", keyInput.text.toString().trim())
            .putString("model", modelInput.text.toString().trim())
            .apply()
    }

    private fun send(text: String) {
        savePrefs()
        val svc = AgentService.instance
        if (svc == null) {
            toast("ابتدا سرویس دسترسی‌پذیری را فعال کنید")
            refreshStatus()
            return
        }
        svc.submit(text)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("تشخیص گفتار روی این گوشی در دسترس نیست")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { toast("گوش می‌دهم…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { toast("خطا در تشخیص گفتار (کد $error)") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) send(t)
            }
        })
        recognizer = r
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        r.startListening(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
    }
}
