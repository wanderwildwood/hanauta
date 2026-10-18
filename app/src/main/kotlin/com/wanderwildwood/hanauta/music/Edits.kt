package com.wanderwildwood.hanauta.music

/**
 * The changes the editor makes to a tune, each one taking the notes and the chosen note's
 * index and giving back the new notes and the index to keep chosen. The notes stay in order
 * and never overlap.
 */
object Edits {
    data class Result(val notes: List<Note>, val selected: Int)

    fun move(notes: List<Note>, i: Int, semitones: Int): Result {
        val n = notes[i]
        val p = (n.pitch + semitones).coerceIn(21, 108)
        return Result(notes.replace(i, n.copy(pitch = p)), i)
    }

    /** Longer or shorter by [steps] of [grid]; never past the next note, never under one step. */
    fun lengthen(notes: List<Note>, i: Int, steps: Int, grid: Int): Result {
        val n = notes[i]
        val limit = notes.getOrNull(i + 1)?.start ?: Int.MAX_VALUE
        val length = (n.length + steps * grid).coerceAtLeast(grid).coerceAtMost(limit - n.start).coerceAtLeast(1)
        return Result(notes.replace(i, n.copy(length = length)), i)
    }

    /** This note and the next become one, at this one's pitch, running to the end of the next. */
    fun mergeNext(notes: List<Note>, i: Int): Result {
        val next = notes.getOrNull(i + 1) ?: return Result(notes, i)
        val n = notes[i]
        val merged = n.copy(length = next.end - n.start)
        return Result(notes.toMutableList().apply { removeAt(i + 1); set(i, merged) }, i)
    }

    /** In two at the step of [grid] nearest its middle; a note one step long stays whole. */
    fun split(notes: List<Note>, i: Int, grid: Int): Result {
        val n = notes[i]
        if (n.length < 2 * grid) return Result(notes, i)
        val steps = n.length / grid
        val first = (steps / 2) * grid
        val a = n.copy(length = first)
        val b = Note(n.start + first, n.length - first, n.pitch)
        return Result(notes.toMutableList().apply { set(i, a); add(i + 1, b) }, i)
    }

    fun delete(notes: List<Note>, i: Int): Result {
        val out = notes.toMutableList().apply { removeAt(i) }
        return Result(out, if (out.isEmpty()) -1 else minOf(i, out.size - 1))
    }

    private fun List<Note>.replace(i: Int, n: Note) = toMutableList().apply { set(i, n) }
}

/** The editor's history: each change pushed, Undo pops back to the one before. */
class Undo<T>(private val limit: Int = 100) {
    private val stack = ArrayDeque<T>()
    val canUndo get() = stack.isNotEmpty()

    fun push(before: T) {
        stack.addLast(before)
        while (stack.size > limit) stack.removeFirst()
    }

    fun pop(): T? = stack.removeLastOrNull()

    fun clear() = stack.clear()
}
