package com.wanderwildwood.hanauta.data

import android.content.Context
import com.wanderwildwood.hanauta.music.Grid

/** How the next take is set up, and two switches. One private preferences file. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("hanauta", Context.MODE_PRIVATE)

    /** Beats a minute for the click. */
    var tempo: Int
        get() = p.getInt("tempo", 100)
        set(v) = p.edit().putInt("tempo", v.coerceIn(MIN_TEMPO, MAX_TEMPO)).apply()

    var beatsPerBar: Int
        get() = p.getInt("beats", 4)
        set(v) = p.edit().putInt("beats", v).apply()

    /** The click on while recording; off, the tune is sung in its own time. */
    var click: Boolean
        get() = p.getBoolean("click", true)
        set(v) = p.edit().putBoolean("click", v).apply()

    var grid: Grid
        get() = runCatching { Grid.valueOf(p.getString("grid", null) ?: "") }.getOrDefault(Grid.Eighth)
        set(v) = p.edit().putString("grid", v.name).apply()

    /**
     * Whether the screens turn sideways when the phone does. The Kompakt's own settings have no
     * auto-rotate to follow, so the app watches the phone itself.
     */
    var turnWithPhone: Boolean
        get() = p.getBoolean(TURN_WITH_PHONE, true)
        set(v) = p.edit().putBoolean(TURN_WITH_PHONE, v).apply()

    /** Calls [then] whenever the turning switch changes; keep the listener, the preferences hold it weakly. */
    fun onTurnChanged(then: (Boolean) -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TURN_WITH_PHONE) then(turnWithPhone)
        }
        p.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun stopWatching(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        p.unregisterOnSharedPreferenceChangeListener(l)

    /** The recording played under the notes, quieter. */
    var playVoice: Boolean
        get() = p.getBoolean("play_voice", true)
        set(v) = p.edit().putBoolean("play_voice", v).apply()

    companion object {
        const val MIN_TEMPO = 40
        const val MAX_TEMPO = 200
        private const val TURN_WITH_PHONE = "turn_with_phone"
    }
}
