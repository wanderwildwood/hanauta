package com.wanderwildwood.hanauta.music

import kotlin.math.roundToInt

/**
 * The tune as sheet music, MusicXML 4.0, one part on one staff: MuseScore, Dorico and most
 * notation programs open it. Notes that cross a bar line, or last a length no single note
 * value writes, are written as tied notes.
 */
object MusicXml {
    /** Divisions to a quarter: twelve takes sixteenths and eighth-note triplets alike. */
    private const val DIV = 12
    private const val TICKS_PER_DIV = PPQ / DIV

    /** A note value: its length in divisions, its type, a dot, and whether it is a triplet. */
    private class Value(val div: Int, val type: String, val dot: Boolean = false, val triplet: Boolean = false)

    private val VALUES = listOf(
        Value(48, "whole"), Value(36, "half", dot = true), Value(24, "half"),
        Value(18, "quarter", dot = true), Value(12, "quarter"), Value(9, "eighth", dot = true),
        Value(8, "quarter", triplet = true), Value(6, "eighth"), Value(4, "eighth", triplet = true),
        Value(3, "16th"), Value(2, "16th", triplet = true), Value(1, "32nd", triplet = true),
    )

    /**
     * For each length up to a bar of 4/4, the fewest values that add up to it. A length the
     * plain values can make (a multiple of a sixteenth) is made of them alone; triplets only
     * for one they cannot.
     */
    private val SPLITS: Array<List<Value>> = run {
        fun table(values: List<Value>): Array<List<Value>?> {
            val best = arrayOfNulls<List<Value>>(DIV * 4 + 1)
            best[0] = emptyList()
            for (d in 1..DIV * 4) {
                var pick: List<Value>? = null
                for (v in values) {
                    if (v.div > d) continue
                    val rest = best[d - v.div] ?: continue
                    if (pick == null || rest.size + 1 < pick.size) pick = listOf(v) + rest
                }
                best[d] = pick?.sortedByDescending { it.div }
            }
            return best
        }
        val plain = table(VALUES.filter { !it.triplet })
        val all = table(VALUES)
        Array(DIV * 4 + 1) { d -> (if (d % 3 == 0) plain[d] else null) ?: all[d] ?: emptyList() }
    }

    fun write(score: Score): String {
        val bar = DIV * score.beatsPerBar
        val notes = score.notes.sortedBy { it.start }.map {
            val a = (it.start.toFloat() / TICKS_PER_DIV).roundToInt()
            val b = maxOf(a + 1, (it.end.toFloat() / TICKS_PER_DIV).roundToInt())
            Triple(a, b, it.pitch)
        }
        val end = notes.maxOfOrNull { it.second } ?: 0
        val bars = maxOf(1, (end + bar - 1) / bar)
        val flats = NoteNames.flats(score.key)
        val clef = if (notes.isNotEmpty() && notes.map { it.third }.sorted()[notes.size / 2] < 55) "F" to 4 else "G" to 2

        val x = StringBuilder()
        x.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        x.append("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""").append('\n')
        x.append("""<score-partwise version="4.0">""").append('\n')
        x.append("  <work><work-title>").append(escape(score.name)).append("</work-title></work>\n")
        x.append("  <part-list><score-part id=\"P1\"><part-name>Melody</part-name></score-part></part-list>\n")
        x.append("  <part id=\"P1\">\n")

        var n = 0 // the next note not yet reached
        for (m in 0 until bars) {
            val from = m * bar
            val to = from + bar
            x.append("    <measure number=\"").append(m + 1).append("\">\n")
            if (m == 0) {
                x.append("      <attributes><divisions>").append(DIV).append("</divisions>")
                x.append("<key><fifths>").append(score.key.fifths).append("</fifths>")
                if (!score.key.chromatic) x.append("<mode>").append(if (score.key.minor) "minor" else "major").append("</mode>")
                x.append("</key>")
                x.append("<time><beats>").append(score.beatsPerBar).append("</beats><beat-type>4</beat-type></time>")
                x.append("<clef><sign>").append(clef.first).append("</sign><line>").append(clef.second).append("</line></clef></attributes>\n")
                x.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
                    .append(score.tempo.roundToInt()).append("</per-minute></metronome></direction-type><sound tempo=\"")
                    .append(score.tempo.roundToInt()).append("\"/></direction>\n")
            }

            var t = from
            var any = false
            while (t < to) {
                while (n < notes.size && notes[n].second <= t) n++
                val note = notes.getOrNull(n)
                if (note == null || note.first >= to) {
                    // Rest to the end of the bar.
                    if (!any && t == from) {
                        x.append("      <note><rest measure=\"yes\"/><duration>").append(bar).append("</duration></note>\n")
                    } else rest(x, to - t)
                    t = to
                } else if (note.first > t) {
                    rest(x, note.first - t)
                    t = note.first
                } else {
                    val stop = minOf(note.second, to)
                    val tiedIn = note.first < t
                    val tiedOut = note.second > to
                    pitched(x, stop - t, note.third, flats, tiedIn, tiedOut)
                    any = true
                    t = stop
                }
            }
            x.append("    </measure>\n")
        }
        x.append("  </part>\n</score-partwise>\n")
        return x.toString()
    }

    private fun rest(x: StringBuilder, d: Int) {
        for (v in SPLITS[d.coerceAtMost(SPLITS.size - 1)]) {
            x.append("      <note><rest/><duration>").append(v.div).append("</duration>")
            value(x, v)
            x.append("</note>\n")
        }
    }

    private fun pitched(x: StringBuilder, d: Int, pitch: Int, flats: Boolean, tiedIn: Boolean, tiedOut: Boolean) {
        val pieces = SPLITS[d.coerceAtMost(SPLITS.size - 1)]
        val name = NoteNames.pitchClass(pitch, flats)
        val step = name.substring(0, 1)
        val alter = when { name.endsWith("♯") -> 1; name.endsWith("♭") -> -1; else -> 0 }
        val octave = pitch / 12 - 1
        for ((i, v) in pieces.withIndex()) {
            val start = tiedOut || i < pieces.size - 1
            val stop = tiedIn || i > 0
            x.append("      <note><pitch><step>").append(step).append("</step>")
            if (alter != 0) x.append("<alter>").append(alter).append("</alter>")
            x.append("<octave>").append(octave).append("</octave></pitch>")
            x.append("<duration>").append(v.div).append("</duration>")
            if (stop) x.append("<tie type=\"stop\"/>")
            if (start) x.append("<tie type=\"start\"/>")
            value(x, v)
            if (start || stop) {
                x.append("<notations>")
                if (stop) x.append("<tied type=\"stop\"/>")
                if (start) x.append("<tied type=\"start\"/>")
                x.append("</notations>")
            }
            x.append("</note>\n")
        }
    }

    private fun value(x: StringBuilder, v: Value) {
        x.append("<type>").append(v.type).append("</type>")
        if (v.dot) x.append("<dot/>")
        if (v.triplet) x.append("<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
