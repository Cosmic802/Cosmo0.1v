package com.cosmic802.cosmo

/** Private body profile and safe-guidance rules that are added to the AI prompt. */
object Profile {

    private const val ROUTINE =
        "Daily routine reference: Morning: drink water, clean tongue, brush teeth 2 minutes, moisturizer plus SPF 30-50, lip balm. " +
            "Day: keep back straight, drink water through the day, regular balanced meals, 30-45 minutes of activity. " +
            "Evening: wash face gently, moisturizer, floss and brush, screens off 30-60 minutes before bed, sleep at a steady time. " +
            "Weekly: gentle exfoliation once or twice, trim nails, change pillowcase.\n"

    fun isMinor(): Boolean {
        val a = Prefs.age.trim().toIntOrNull() ?: return false
        return a in 1..17
    }

    fun personaBlock(): String = buildString {
        val parts = ArrayList<String>()
        if (Prefs.age.isNotBlank()) parts.add("age ${Prefs.age.trim()}")
        if (Prefs.heightCm.isNotBlank()) parts.add("height ${Prefs.heightCm.trim()} cm")
        if (Prefs.weightKg.isNotBlank()) parts.add("weight ${Prefs.weightKg.trim()} kg")
        if (parts.isNotEmpty()) {
            append("Private user profile (use only when relevant): ").append(parts.joinToString(", ")).append(".\n")
        }
        if (isMinor()) {
            append(
                "The user is a teenager. Keep every answer age-appropriate. " +
                    "Never suggest weight loss, dieting, calorie limits, fasting, supplements, extreme exercise or risky ways to change appearance, " +
                    "and never give weight or body-fat targets. For food and sleep, encourage regular balanced meals and 8 to 10 hours of sleep. " +
                    "For skin, hair, hygiene and posture, give simple safe basics. " +
                    "If the user worries about their body, weight or looks, be kind and suggest talking to a parent, school nurse or doctor. " +
                    "Do not encourage extreme looksmaxxing.\n"
            )
            append(ROUTINE)
        }
    }

    fun localBlock(): String {
        val parts = ArrayList<String>()
        if (Prefs.age.isNotBlank()) parts.add("age ${Prefs.age.trim()}")
        if (Prefs.heightCm.isNotBlank()) parts.add("${Prefs.heightCm.trim()} cm")
        if (Prefs.weightKg.isNotBlank()) parts.add("${Prefs.weightKg.trim()} kg")
        val base = if (parts.isEmpty()) "" else " User: ${parts.joinToString(", ")}."
        val safe = if (isMinor()) " The user is a teenager: no dieting, weight-loss or body-fat advice; suggest a parent or doctor for body worries." else ""
        return base + safe
    }
}
