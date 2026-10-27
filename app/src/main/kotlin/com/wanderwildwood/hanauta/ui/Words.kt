package com.wanderwildwood.hanauta.ui

import android.content.Context
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.music.Grid
import com.wanderwildwood.hanauta.music.Key
import com.wanderwildwood.hanauta.music.NoteNames
import com.wanderwildwood.hanauta.music.PPQ
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** The app's words for keys, grids, lengths and places in the tune. */
object Words {
    fun key(context: Context, key: Key): String = when {
        key.chromatic -> context.getString(R.string.key_chromatic)
        key.minor -> context.getString(R.string.key_minor, tonic(key))
        else -> context.getString(R.string.key_major, tonic(key))
    }

    /** A tonic spelt the way its key signature has it: F♯ major, but E♭ major. */
    private fun tonic(key: Key) = NoteNames.pitchClass(key.tonic, NoteNames.flats(key))

    fun grid(context: Context, grid: Grid): String = context.getString(
        when (grid) {
            Grid.Quarter -> R.string.grid_quarter
            Grid.Eighth -> R.string.grid_eighth
            Grid.Sixteenth -> R.string.grid_sixteenth
            Grid.Triplet -> R.string.grid_triplet
        },
    )

    fun gridShort(context: Context, grid: Grid): String = context.getString(
        when (grid) {
            Grid.Quarter -> R.string.grid_quarter_short
            Grid.Eighth -> R.string.grid_eighth_short
            Grid.Sixteenth -> R.string.grid_sixteenth_short
            Grid.Triplet -> R.string.grid_triplet_short
        },
    )

    /** The day and time a take was made, which is the name it starts with: "Oct 24, 2026, 3:05 PM". */
    fun recordedAt(id: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(id))

    /** "1:05". */
    fun duration(seconds: Float): String {
        val s = seconds.roundToInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun tempo(context: Context, tempo: Float) = context.getString(R.string.record_bpm, tempo.roundToInt())

    /** Where a tick falls: its bar from 1, and its beat from 1, with a half as "2½". */
    fun place(tick: Int, beatsPerBar: Int): Pair<Int, String> {
        val bar = tick / (PPQ * beatsPerBar) + 1
        val inBar = tick % (PPQ * beatsPerBar)
        val beat = inBar / PPQ + 1
        val part = inBar % PPQ
        val frac = when (part) {
            0 -> ""
            PPQ / 4 -> "¼"
            PPQ / 3 -> "⅓"
            PPQ / 2 -> "½"
            2 * PPQ / 3 -> "⅔"
            3 * PPQ / 4 -> "¾"
            else -> "+"
        }
        return bar to "$beat$frac"
    }
}
