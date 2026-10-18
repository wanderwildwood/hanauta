package com.wanderwildwood.hanauta.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the take under way has got to. The screens watch this; the service moves it on. */
sealed interface Stage {
    data object Idle : Stage
    /** The bar of clicks before the tune: [beat] is 1 to the bar's beats. */
    data class CountIn(val beat: Int, val beats: Int) : Stage
    data class Listening(val seconds: Int, val loud: Boolean) : Stage
    /** Reading the notes out of the recording; [share] from 0 to 1. */
    data class Finding(val share: Float) : Stage
    data class Done(val takeId: Long) : Stage
    data class Failed(val reason: Reason) : Stage
}

enum class Reason { NoMicrophone, Unreadable, NoNotes }

/** What the next take is recorded with. */
data class Setup(val tempo: Int, val beatsPerBar: Int, val click: Boolean)

object Recording {
    internal val stage = MutableStateFlow<Stage>(Stage.Idle)
    val now: StateFlow<Stage> = stage

    fun start(context: Context, setup: Setup) {
        if (busy) return
        stage.value = if (setup.click) Stage.CountIn(1, setup.beatsPerBar) else Stage.Listening(0, false)
        context.startForegroundService(
            Intent(context, RecordService::class.java)
                .setAction(RecordService.RECORD)
                .putExtra("tempo", setup.tempo)
                .putExtra("beats", setup.beatsPerBar)
                .putExtra("click", setup.click),
        )
    }

    /** A sound file made somewhere else, read for its notes as a take sung in its own time. */
    fun import(context: Context, uri: Uri, name: String?, beatsPerBar: Int) {
        if (busy) return
        stage.value = Stage.Finding(0f)
        context.startForegroundService(
            Intent(context, RecordService::class.java)
                .setAction(RecordService.IMPORT)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("name", name)
                .putExtra("beats", beatsPerBar),
        )
    }

    fun stop() {
        RecordService.stopListening = true
    }

    /** The take has been opened, or the failure read: back to nothing under way. */
    fun clear() {
        val s = stage.value
        if (s is Stage.Done || s is Stage.Failed) stage.value = Stage.Idle
    }

    val busy get() = stage.value.let { it is Stage.CountIn || it is Stage.Listening || it is Stage.Finding }
}
