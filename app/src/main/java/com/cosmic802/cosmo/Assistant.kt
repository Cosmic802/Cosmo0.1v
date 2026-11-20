package com.cosmic802.cosmo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Chat commands that work offline: modes, calls, texts, emails, opening apps. */
object Assistant {

    private val MODE_RE = Regex("\\b(study|game|gaming|sleep|normal)\\s+mode\\b|\\bmode\\s+(study|game|gaming|sleep|normal)\\b")
    private val CALL_RE = Regex("^(?:please\\s+)?(?:call|dial|duud(?:ah)?)\\s+(.+)$")
    private val TEXT_RE = Regex("^(?:please\\s+)?(?:text|sms|send (?:a )?(?:message|msg|text) to)\\s+(.+)$")
    private val EMAIL_RE = Regex("^(?:please\\s+)?(?:email|mail)\\s+(.+)$")
    private val FIND_RE = Regex("^(?:find|who is|search)\\s+(?:contact\\s+)?(.+)$")
    private val OPEN_RE = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+)$")

    private val APPS = mapOf(
        "messenger" to "com.facebook.orca",
        "gmail" to "com.google.android.gm",
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "messages" to "com.google.android.apps.messaging",
        "contacts" to "com.google.android.contacts",
        "phone" to "com.google.android.dialer",
        "youtube" to "com.google.android.youtube"
    )

    fun tryHandle(ctx: Context, raw: String): String? {
        val t = raw.lowercase(Locale.ROOT).trim().trim('.', '!', '?')
        if (t.isEmpty()) return null

        if (t == "remember" || t == "remember that") {
            return "Tell me what to remember, for example: remember my exam is on Friday."
        }

        MODE_RE.find(t)?.let { m ->
            val word = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val off = Regex("\\b(off|stop|disable|end)\\b").containsMatchIn(t)
            val mode = if (off) CosmoMode.NORMAL else when (word) {
                "study" -> CosmoMode.STUDY
                "game", "gaming" -> CosmoMode.GAME
                "sleep" -> CosmoMode.SLEEP
                else -> CosmoMode.NORMAL
            }
            RootShell.apply(ctx, mode) { ok -> if (ok) ModeState.mode = mode }
            return "Applying ${mode.label} mode, Sir."
        }

        CALL_RE.find(t)?.let { return callOrText(ctx, it.groupValues[1].trim(), "call") }
        TEXT_RE.find(t)?.let { return callOrText(ctx, it.groupValues[1].trim(), "text") }
        EMAIL_RE.find(t)?.let { return email(ctx, it.groupValues[1].trim()) }

        OPEN_RE.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val pkg = APPS.entries.firstOrNull { name.contains(it.key) }?.value ?: return null
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "That app is not installed."
            return launch(ctx, i, "Opening ${name}.")
        }

        FIND_RE.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (!Contacts.hasPermission(ctx)) return null
            val hits = Contacts.phones(ctx, name)
            if (hits.isEmpty()) return null
            return hits.joinToString("\n") { "${it.first}: ${it.second}" }
        }
        return null
    }

    private fun needContacts(ctx: Context): String? =
        if (Contacts.hasPermission(ctx)) null
        else "Allow contacts first: SET tab > PROFILE > ALLOW CONTACTS."

    private fun callOrText(ctx: Context, name: String, what: String): String {
        needContacts(ctx)?.let { return it }
        val hits = Contacts.phones(ctx, name)
        if (hits.isEmpty()) return "No contact found for \"$name\"."
        val (who, number) = hits[0]
        val uri = Uri.parse(if (what == "call") "tel:$number" else "smsto:$number")
        val action = if (what == "call") Intent.ACTION_DIAL else Intent.ACTION_SENDTO
        val extra = if (hits.size > 1) " (${hits.size} matches, using the first)" else ""
        return launch(
            ctx,
            Intent(action, uri),
            if (what == "call") "Dialing $who$extra. Press call to confirm." else "Opening message to $who$extra."
        )
    }

    private fun email(ctx: Context, name: String): String {
        val direct = if (name.contains("@")) name else null
        val addr = direct ?: run {
            needContacts(ctx)?.let { return it }
            Contacts.emails(ctx, name).firstOrNull()?.second
        } ?: return "No email found for \"$name\"."
        return launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr")), "Opening email to $addr.")
    }

    private fun launch(ctx: Context, intent: Intent, ok: String): String =
        try {
            ctx.applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok
        } catch (_: ActivityNotFoundException) {
            "No app found for that."
        } catch (e: Exception) {
            "Could not open it: ${e.message ?: "unknown error"}"
        }
}
