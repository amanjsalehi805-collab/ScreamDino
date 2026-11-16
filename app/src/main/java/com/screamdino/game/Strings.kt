package com.screamdino.game

/** English + Persian UI strings. */
object Strings {
    private val en = mapOf(
        "play" to "PLAY", "settings" to "SETTINGS", "resume" to "RESUME", "menu" to "MENU",
        "restart" to "RESTART", "gameover" to "GAME OVER", "score" to "SCORE", "best" to "BEST",
        "newbest" to "NEW BEST!", "voice" to "Voice control", "sens" to "Mic sensitivity",
        "lang" to "Language", "sound" to "Sound effects", "back" to "BACK", "paused" to "PAUSED",
        "hint" to "Shout or tap to jump!", "level" to "Mic level", "denied" to "Microphone permission denied",
        "sub" to "Shout to jump!"
    )
    private val fa = mapOf(
        "play" to "شروع بازی", "settings" to "تنظیمات", "resume" to "ادامه", "menu" to "منو",
        "restart" to "شروع دوباره", "gameover" to "پایان بازی", "score" to "امتیاز", "best" to "رکورد",
        "newbest" to "رکورد جدید!", "voice" to "کنترل با صدا", "sens" to "حساسیت میکروفون",
        "lang" to "زبان", "sound" to "جلوه‌های صوتی", "back" to "بازگشت", "paused" to "توقف",
        "hint" to "فریاد بزن یا لمس کن!", "level" to "سطح صدا", "denied" to "اجازه میکروفون داده نشد",
        "sub" to "فریاد بزن، بپر!"
    )

    fun t(lang: String, key: String): String = (if (lang == "fa") fa else en)[key] ?: key

    fun digits(lang: String, s: String): String {
        if (lang != "fa") return s
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
        return sb.toString()
    }
}
