package com.cosmic802.cosmo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class CosmoVoiceEngine : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "cosmo_voice"
        private const val NOTIF_ID = 803
        private const val ACTION_STOP = "com.cosmic802.cosmo.VOICE_STOP"
        private const val ARM_WINDOW_MS = 8000L
        private const val SPEAK_WATCHDOG_MS = 15000L

        private val ADD_TASK_RE = Regex("(?:add|new|create)\\s+(?:a\\s+)?task\\s+(.+)")
        private val REMIND_RE = Regex("(?:remind me to|reminder to)\\s+(.+)")
        private val ASK_RE = Regex("\\bask\\s+(.+)")

        @Volatile
        private var instance: CosmoVoiceEngine? = null

        @Volatile
        var onModeChange: ((CosmoMode) -> Unit)? = null

        @Volatile
        var onHeard: ((String) -> Unit)? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            try {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, CosmoVoiceEngine::class.java)
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, CosmoVoiceEngine::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var greeted = false
    private var speaking = false
    private var stopped = false
    private var armedUntil = 0L
    private var errorStreak = 0
    private var offlineFailed = false
    private var utteranceId = 0

    private var cameraManager: CameraManager? = null
    private var torchId: String? = null
    private var torchOn = false
    private var torchCallback: CameraManager.TorchCallback? = null

    private val restartTask = Runnable { beginListening() }
    private val speakWatchdog = Runnable { finishSpeaking() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        CosmoBrain.init(this)
        tts = TextToSpeech(this, this)
        setupTorch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        VoiceState.error = null
        VoiceState.status = "STARTING..."

        try {
            startAsForeground()
        } catch (e: Exception) {
            VoiceState.error = "Cannot start voice service: ${e.message ?: "unknown"}"
            stopSelf()
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoiceState.error = "Mic permission missing. Allow microphone for Cosmo."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            VoiceState.error = "No speech service on this phone. Install Google app / Speech Services."
            stopSelf()
            return START_NOT_STICKY
        }

        if (recognizer == null) {
            createRecognizer()
            scheduleRestart(500)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cosmo Voice", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CosmoVoiceEngine::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cosmo")
            .setContentText("Voice assistant is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- speech recognition ----------

    private fun createRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (stopped) return
        handler.removeCallbacks(restartTask)
        handler.postDelayed(restartTask, delayMs)
    }

    private fun beginListening() {
        if (stopped || speaking) return
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceState.recognitionLocale())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !offlineFailed)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            createRecognizer()
            scheduleRestart(1500)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!speaking) VoiceState.status = if (armedUntil > SystemClock.elapsedRealtime()) {
                "LISTENING FOR COMMAND..."
            } else if (Prefs.wakeEnabled) {
                "LISTENING FOR \"${Prefs.wakeWord.ifBlank { "cosmo" }.uppercase(Locale.ROOT)}\""
            } else {
                "LISTENING..."
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            errorStreak = 0
            VoiceState.error = null
            val list = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            if (list.isNotEmpty()) VoiceState.lastHeard = list[0]
            handle(list)
            if (!speaking) scheduleRestart(300)
        }

        override fun onError(error: Int) {
            if (stopped) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    VoiceState.error = "Mic permission missing. Allow microphone for Cosmo."
                    stopSelf()
                    return
                }
                // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 31+)
                12, 13 -> {
                    errorStreak++
                    if (!offlineFailed) {
                        offlineFailed = true
                        VoiceState.status = "OFFLINE PACK MISSING · USING ONLINE SPEECH"
                        if (!speaking) scheduleRestart(300)
                    } else {
                        VoiceState.error = "Language ${VoiceState.recognitionLocale()} not supported by speech service"
                        if (!speaking) scheduleRestart(minOf(8000L, 1500L * errorStreak))
                    }
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    errorStreak++
                    VoiceState.status = "NO INTERNET · OFFLINE SPEECH PACK NEEDED"
                    if (!speaking) scheduleRestart(minOf(5000L, 1000L * errorStreak))
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    errorStreak++
                    VoiceState.status = "MICROPHONE BUSY OR UNAVAILABLE"
                    if (!speaking) scheduleRestart(minOf(5000L, 1000L * errorStreak))
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (!speaking) scheduleRestart(200)
                }
                else -> {
                    errorStreak++
                    createRecognizer()
                    VoiceState.status = "SPEECH SERVICE ERROR ($error) · RETRYING"
                    if (errorStreak >= 6) {
                        VoiceState.error = "Speech service keeps failing (code $error)"
                    }
                    if (!speaking) scheduleRestart(minOf(5000L, 800L * errorStreak))
                }
            }
        }
    }

    // ---------- command parsing ----------

    private fun handle(candidates: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val armed = now < armedUntil
        for (raw in candidates) {
            val t = raw.lowercase(Locale.ROOT).trim()
            if (t.isEmpty()) continue
            val (hasName, rest) = VoiceState.splitWake(t)
            val live = !Prefs.wakeEnabled || armed
            if (!hasName && !live) continue

            val text = clean(if (hasName) rest else t)
            VoiceState.lastHeard = raw
            onHeard?.invoke(raw)
            armedUntil = 0L

            if (text.isEmpty()) {
                armedUntil = now + ARM_WINDOW_MS
                speak("Yes, Sir?")
                return
            }
            if (runCommand(text)) return
            if (text.length >= 3) {
                askAi(text)
            } else {
                armedUntil = now + ARM_WINDOW_MS
                speak("Yes, Sir?")
            }
            return
        }
    }

    private fun clean(s: String): String = s.trim().trim('.', ',', '?', '!').trim()

    private fun runCommand(t: String): Boolean {
        ADD_TASK_RE.find(t)?.let {
            addTask(clean(it.groupValues[1]))
            return true
        }
        REMIND_RE.find(t)?.let {
            addTask(clean(it.groupValues[1]))
            return true
        }
        ASK_RE.find(t)?.let {
            askAi(clean(it.groupValues[1]))
            return true
        }
        val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.contains("tasks")) {
            listTasks()
            return true
        }
        if (words.contains("briefing")) {
            CosmoBrain.dailyBriefing { speak(spoken(it.text)) }
            return true
        }
        val cmd = parse(t) ?: return false
        execute(cmd)
        return true
    }

    private fun parse(t: String): String? {
        val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return when {
            words.any { it == "flashlight" || it == "torch" } ||
                t.contains("flash light") -> when {
                words.contains("off") -> "torch_off"
                words.contains("on") -> "torch_on"
                else -> "torch"
            }
            words.contains("study") -> "study"
            words.contains("sleep") -> "sleep"
            words.contains("game") || words.contains("gaming") -> "game"
            words.contains("normal") -> "normal"
            else -> null
        }
    }

    private fun execute(cmd: String) {
        when (cmd) {
            "study" -> applyMode(CosmoMode.STUDY, "Study mode engaged, Sir.")
            "game" -> applyMode(CosmoMode.GAME, "Game mode engaged, Sir.")
            "sleep" -> applyMode(CosmoMode.SLEEP, "Sleep mode engaged. Good night, Sir.")
            "normal" -> applyMode(CosmoMode.NORMAL, "Normal mode restored, Sir.")
            "torch_on" -> speak(if (setTorch(true)) "Flashlight on, Sir." else "Flashlight unavailable, Sir.")
            "torch_off" -> speak(if (setTorch(false)) "Flashlight off, Sir." else "Flashlight unavailable, Sir.")
            "torch" -> {
                val target = !torchOn
                speak(
                    if (setTorch(target)) {
                        if (target) "Flashlight on, Sir." else "Flashlight off, Sir."
                    } else {
                        "Flashlight unavailable, Sir."
                    }
                )
            }
        }
    }

    private fun applyMode(mode: CosmoMode, ack: String) {
        RootShell.apply(this, mode) { ok ->
            if (stopped) return@apply
            if (ok) {
                onModeChange?.invoke(mode)
                speak(ack)
            } else {
                speak("I could not obtain root access, Sir.")
            }
        }
    }

    // ---------- tasks and AI ----------

    private fun addTask(title: String) {
        Thread {
            val ok = TaskStore.add(title)
            handler.post {
                speak(if (ok) "Task added, Sir." else "I could not save the task, Sir.")
            }
        }.start()
    }

    private fun listTasks() {
        Thread {
            val open = TaskStore.list().filter { !it.done }
            val msg = if (open.isEmpty()) {
                "You have no open tasks, Sir."
            } else {
                "You have ${open.size} open tasks, Sir. " +
                    open.take(3).joinToString(". ") { it.title }
            }
            handler.post { speak(msg) }
        }.start()
    }

    private fun askAi(question: String) {
        if (question.isEmpty()) {
            speak("What shall I ask, Sir?")
            return
        }
        CosmoBrain.ask(question, "chat") { speak(spoken(it.text)) }
    }

    private fun spoken(s: String): String =
        s.replace(Regex("[*#_`]"), "").take(400)

    // ---------- flashlight ----------

    private fun setupTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager = cm
            torchId = cm.cameraIdList.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) torchOn = enabled
                }
            }
            torchCallback = cb
            cm.registerTorchCallback(cb, handler)
        } catch (_: Exception) {
        }
    }

    private fun setTorch(on: Boolean): Boolean {
        val id = torchId ?: return false
        return try {
            cameraManager?.setTorchMode(id, on)
            torchOn = on
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- text to speech ----------

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return

        VoiceState.checkTtsLanguage(t)
        pickVoice(t)
        t.setPitch(0.85f)
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { finishSpeaking() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { finishSpeaking() }
            }
        })
        ttsReady = true
        if (!greeted) {
            greeted = true
            speak("Voice systems online, Sir.")
        }
    }

    private fun isMaleVoiceName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("gbd") || n.contains("rjs") ||
            (n.contains("male") && !n.contains("female"))
    }

    private fun pickVoice(t: TextToSpeech) {
        try {
            val loc = t.voice?.locale ?: VoiceState.ttsLocale()
            val voices = t.voices
                ?.filter { it.locale.language == loc.language && !it.isNetworkConnectionRequired }
                .orEmpty()
            val v = voices.firstOrNull { isMaleVoiceName(it.name) && it.locale.country == loc.country }
                ?: voices.firstOrNull { isMaleVoiceName(it.name) }
                ?: voices.firstOrNull { it.locale.country == loc.country }
                ?: voices.firstOrNull()
            if (v != null) t.voice = v
        } catch (_: Exception) {
        }
    }

    private fun speak(text: String) {
        val t = tts
        if (stopped || t == null || !ttsReady) return
        speaking = true
        VoiceState.status = "SPEAKING..."
        handler.removeCallbacks(restartTask)
        handler.removeCallbacks(speakWatchdog)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        utteranceId++
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cosmo-$utteranceId")
        if (res == TextToSpeech.ERROR) {
            finishSpeaking()
        } else {
            handler.postDelayed(speakWatchdog, SPEAK_WATCHDOG_MS)
        }
    }

    private fun finishSpeaking() {
        handler.removeCallbacks(speakWatchdog)
        speaking = false
        scheduleRestart(500)
    }

    // ---------- lifecycle ----------

    override fun onDestroy() {
        stopped = true
        instance = null
        VoiceState.status = "OFF"
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            torchCallback?.let { cameraManager?.unregisterTorchCallback(it) }
        } catch (_: Exception) {
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }
}
