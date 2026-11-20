package com.cosmic802.cosmo

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline brain.
 *  1. Optional on-device LLM (Gemma .task model picked in Settings, run by MediaPipe).
 *  2. Built-in rule engine that works with no model at all (tasks, habits, time, notes).
 */
object LocalAI {

    private var engine: LlmInference? = null
    private var loadedPath: String = ""

    fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "models"), "local_model.task")

    fun isModelReady(): Boolean =
        Prefs.modelPath.isNotEmpty() && File(Prefs.modelPath).let { it.exists() && it.length() > 0 }

    @Synchronized
    fun release() {
        engine = null
        loadedPath = ""
    }

    /** Returns null when no usable local model exists or it failed. Call from a background thread. */
    @Synchronized
    fun generate(ctx: Context, system: String, userPrompt: String): String? {
        if (!isModelReady()) return null
        return try {
            if (engine == null || loadedPath != Prefs.modelPath) {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(Prefs.modelPath)
                    .setMaxTokens(1024)
                    .build()
                engine = LlmInference.createFromOptions(ctx.applicationContext, opts)
                loadedPath = Prefs.modelPath
            }
            val prompt = "<start_of_turn>user\n$system\n\n$userPrompt<end_of_turn>\n<start_of_turn>model\n"
            engine?.generateResponse(prompt)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            engine = null
            loadedPath = ""
            null
        }
    }

    // ---------------- rule engine (no model needed) ----------------

    fun isMongolian(q: String): Boolean {
        when (Prefs.language) {
            "MN" -> return true
            "EN" -> return false
        }
        if (q.any { it in '\u0400'..'\u04FF' }) return true
        val l = q.lowercase(Locale.ROOT)
        val hints = listOf("sain", "bayrlalaa", "yu ", "yamar", "ajil", "daalgavar", "minii", "bi ", "uu?", " uu")
        return hints.any { l.contains(it) }
    }

    private fun who(): String =
        Prefs.userName.ifBlank { if (Prefs.style == "BUTLER") "Sir" else "" }

    fun ruleBased(question: String, kind: String): String {
        val mn = isMongolian(question)
        fun t(en: String, m: String) = if (mn) m else en
        val q = question.lowercase(Locale.ROOT).trim()
        val name = who()
        val tag = if (name.isEmpty()) "" else ", $name"

        if (kind == "briefing") return offlineBriefing(mn)
        if (kind == "discipline") return offlineDiscipline(mn)

        val greets = listOf("hello", "hi", "hey", "sain", "сайн", "сайхан")
        if (greets.any { q == it || q.startsWith("$it ") || q.startsWith("$it,") }) {
            return t(
                "Hello$tag. I am offline at the moment, but I can still help with tasks, habits and notes.",
                "Сайн уу$tag. Би одоо офлайн байна, гэхдээ даалгавар, зуршил, тэмдэглэлд тусалж чадна."
            )
        }

        if (listOf("about me", "know about me", "who am i", "my name", "minii ner", "намайг", "миний нэр").any { q.contains(it) }) {
            val facts = CosmoBrain.listFacts()
            val sb = StringBuilder()
            if (Prefs.userName.isNotBlank()) sb.append(t("Your name is ${Prefs.userName}.", "Таны нэр ${Prefs.userName}.")).append('\n')
            if (Prefs.about.isNotBlank()) sb.append(Prefs.about).append('\n')
            facts.forEach { sb.append("- ").append(it).append('\n') }
            return if (sb.isBlank()) {
                t("I know nothing about you yet. Fill in your profile in Settings, or say \"remember ...\".",
                    "Би таны талаар одоохондоо юу ч мэдэхгүй. Тохиргоонд профайлаа бөглөнө үү, эсвэл \"сана ...\" гэж хэлээрэй.")
            } else sb.toString().trim()
        }

        if (listOf("task", "todo", "to-do", "ajil", "daalgavar", "даалгавар", "ажил").any { q.contains(it) }) {
            val open = TaskStore.list().filter { !it.done }
            return if (open.isEmpty()) {
                t("You have no open tasks$tag.", "Танд нээлттэй даалгавар алга.")
            } else {
                t("You have ${open.size} open tasks: ", "Танд ${open.size} нээлттэй даалгавар байна: ") +
                    open.take(5).joinToString("; ") { it.title }
            }
        }

        if (listOf("habit", "water", "steps", "zurshil", "зуршил", "ус ", "алхам").any { q.contains(it) }) {
            return habitLine(mn)
        }

        if (listOf("time", "tsag", "цаг").any { q.contains(it) }) {
            val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            return t("It is $now$tag.", "Одоо $now цаг.")
        }

        if (listOf("date", "today", "ognoo", "огноо", "өнөөдөр").any { q.contains(it) }) {
            return t("Today is ${CosmoBrain.todayKey()}.", "Өнөөдөр ${CosmoBrain.todayKey()}.")
        }

        return t(
            "I am offline and no local AI model is installed, so I can only help with tasks, habits, time and your saved notes. Add a local model in Settings for full offline chat.",
            "Би офлайн байна, дотоод AI загвар суулгаагүй тул зөвхөн даалгавар, зуршил, цаг болон хадгалсан тэмдэглэлд тусалж чадна. Бүрэн офлайн чат хийхийн тулд Тохиргоонд дотоод загвар нэмнэ үү."
        )
    }

    private fun habitLine(mn: Boolean): String {
        val d = HabitStore.load()
        val water = HabitStore.water(d)
        val steps = HabitStore.steps(d)
        val pct = (HabitStore.progress(d) * 100).toInt()
        return if (mn) {
            "Ус $water мл (зорилго ${HabitStore.WATER_GOAL}), алхам $steps (зорилго ${HabitStore.STEPS_GOAL}), зуршил $pct%."
        } else {
            "Water $water ml of ${HabitStore.WATER_GOAL}, steps $steps of ${HabitStore.STEPS_GOAL}, habits $pct% done."
        }
    }

    private fun offlineBriefing(mn: Boolean): String {
        val open = TaskStore.list().filter { !it.done }
        val tasks = if (open.isEmpty()) {
            if (mn) "Нээлттэй даалгавар алга." else "No open tasks."
        } else {
            (if (mn) "Тэргүүлэх даалгавар: " else "Top tasks: ") + open.take(3).joinToString("; ") { it.title }
        }
        val head = if (mn) "Өглөөний мэнд${if (Prefs.userName.isNotBlank()) ", ${Prefs.userName}" else ""}. (офлайн)" else
            "Good day${if (who().isNotEmpty()) ", ${who()}" else ""}. (offline)"
        return "$head\n${habitLine(mn)}\n$tasks"
    }

    private fun offlineDiscipline(mn: Boolean): String {
        val d = HabitStore.load()
        val missing = ArrayList<String>()
        if (HabitStore.water(d) < HabitStore.WATER_GOAL) missing.add(if (mn) "ус" else "water")
        if (HabitStore.steps(d) < HabitStore.STEPS_GOAL) missing.add(if (mn) "алхам" else "steps")
        return if (missing.isEmpty()) {
            if (mn) "Өнөөдрийн гол зорилгууд биелсэн байна. Ингэж үргэлжлүүл." else "Today's main goals are met. Keep it up."
        } else {
            (if (mn) "Дутуу байгаа: " else "Still short on: ") + missing.joinToString(", ") +
                (if (mn) ". Одоо хий." else ". Do it now.")
        }
    }
}
