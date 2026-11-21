package com.cosmic802.cosmo

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline brain.
 *  1. Optional on-device LLM (any .gguf model picked in Settings, run by llama.cpp).
 *  2. Built-in rule engine that works with no model at all (tasks, habits, time, notes).
 */
object LocalAI {

    fun modelFile(ctx: Context): File = LlamaEngine.modelFile(ctx)

    fun isModelReady(): Boolean = LlamaEngine.isReady()

    fun release() = LlamaEngine.release()

    /** Returns null when no usable local model exists or it failed. Call from a background thread. */
    fun generate(ctx: Context, system: String, userPrompt: String): String? =
        LlamaEngine.generate(ctx, system, userPrompt)

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

    fun ruleBased(question: String, kind: String, cloudFailed: Boolean = false): String {
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

        if (listOf("your name", "who are you", "ner chin", "чиний нэр", "чи хэн").any { q.contains(it) }) {
            return t("I am Cosmo, your personal assistant$tag.", "Би Cosmo, таны хувийн туслах.")
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

        if (listOf("screen time", "screentime", "screen", "дэлгэц", "экран", "dalgets").any { q.contains(it) }) {
            return screenLine(mn)
        }

        if (listOf("sleep", "slept", "untl", "untsan", "унт").any { q.contains(it) }) {
            return sleepLine(mn)
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

        return if (cloudFailed) {
            t(
                "I could not reach Gemini just now, and no local AI model is installed, so I can only help with tasks, habits, time and your saved notes.",
                "Би Gemini-тэй холбогдож чадсангүй, дотоод AI загвар ч суулгаагүй тул зөвхөн даалгавар, зуршил, цаг болон хадгалсан тэмдэглэлд тусалж чадна."
            )
        } else {
            t(
                "I am offline and no local AI model is installed, so I can only help with tasks, habits, time and your saved notes. Add a local model in Settings for full offline chat.",
                "Би офлайн байна, дотоод AI загвар суулгаагүй тул зөвхөн даалгавар, зуршил, цаг болон хадгалсан тэмдэглэлд тусалж чадна. Бүрэн офлайн чат хийхийн тулд Тохиргоонд дотоод загвар нэмнэ үү."
            )
        }
    }

    private fun screenLine(mn: Boolean): String {
        if (!Tracker.hasUsageAccess()) {
            return if (mn) "Дэлгэцийн хугацааг харахын тулд STATS хэсэгт хандах эрх олгоно уу."
            else "Allow usage access in the STATS tab so I can read your screen time."
        }
        val (min, apps) = Tracker.screenToday()
        val top = apps.take(3).joinToString(", ") { it.label + " " + Tracker.fmtMin(it.minutes) }
        val limit = Prefs.screenLimitH.toInt()
        return if (mn) {
            "Өнөөдрийн дэлгэцийн хугацаа ${Tracker.fmtMin(min)} (хязгаар ${limit}ц). Топ: $top."
        } else {
            "Screen time today: ${Tracker.fmtMin(min)} (limit ${limit}h). Top: $top."
        }
    }

    private fun sleepLine(mn: Boolean): String {
        val map = Tracker.sleepMap()
        val last = map.entries.maxByOrNull { it.key }
            ?: return if (mn) "Унтлагын мэдээлэл хараахан алга." else "No sleep data yet."
        val avg = map.entries.sortedByDescending { it.key }.take(7).map { it.value }.average()
        val l = String.format(Locale.US, "%.1f", last.value)
        val a = String.format(Locale.US, "%.1f", avg)
        return if (mn) "Сүүлийн унтлага ${l} цаг (${last.key}), 7 хоногийн дундаж ${a} цаг."
        else "Last tracked sleep: ${l} h (${last.key}). 7-night average: ${a} h."
    }

    private fun habitLine(mn: Boolean): String {
        val d = HabitStore.load()
        val water = HabitStore.water(d)
        val steps = HabitStore.steps(d)
        val pct = (HabitStore.progress(d) * 100).toInt()
        val move = if (Tracker.hasActivityPermission()) " " + Tracker.walkingStatus() + "." else ""
        return if (mn) {
            "Ус $water мл (зорилго ${HabitStore.WATER_GOAL}), алхам $steps (зорилго ${HabitStore.STEPS_GOAL}), зуршил $pct%.$move"
        } else {
            "Water $water ml of ${HabitStore.WATER_GOAL}, steps $steps of ${HabitStore.STEPS_GOAL}, habits $pct% done.$move"
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
