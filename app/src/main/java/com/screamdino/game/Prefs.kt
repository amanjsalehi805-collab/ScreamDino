package com.screamdino.game

import android.content.Context
import java.util.Locale

/** Local settings + high score. Nothing leaves the device. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("scream_dino", Context.MODE_PRIVATE)

    var highScore: Int
        get() = sp.getInt("hi", 0)
        set(v) { sp.edit().putInt("hi", v).apply() }

    var sensitivity: Int
        get() = sp.getInt("sens", 40)
        set(v) { sp.edit().putInt("sens", v.coerceIn(0, 100)).apply() }

    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) { sp.edit().putBoolean("voice", v).apply() }

    var soundEnabled: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    var lang: String
        get() = sp.getString("lang", if (Locale.getDefault().language == "fa") "fa" else "en") ?: "en"
        set(v) { sp.edit().putString("lang", v).apply() }
}
