package com.wanderwildwood.hanauta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.wanderwildwood.hanauta.audio.Recording
import com.wanderwildwood.hanauta.audio.Stage

sealed interface Screen {
    data object Home : Screen
    data object Record : Screen
    data object Settings : Screen
    data class Take(val id: Long) : Screen
}

/** Kept across the activity being made again, which an e-ink phone does on any rotation. */
private val ScreenSaver = Saver<Screen, String>(
    save = { s ->
        when (s) {
            Screen.Home -> "home"
            Screen.Record -> "record"
            Screen.Settings -> "settings"
            is Screen.Take -> "take:${s.id}"
        }
    },
    restore = { v ->
        when {
            v == "record" -> Screen.Record
            v == "settings" -> Screen.Settings
            v.startsWith("take:") -> v.removePrefix("take:").toLongOrNull()?.let { Screen.Take(it) } ?: Screen.Home
            else -> Screen.Home
        }
    },
)

@Composable
fun HumApp() {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
    val stage by Recording.now.collectAsState()

    // A take finished, recorded or read in: open it.
    LaunchedEffect(stage) {
        val s = stage
        if (s is Stage.Done) {
            Recording.clear()
            screen = Screen.Take(s.takeId)
        }
    }
    // A take under way is always shown, wherever the app was left.
    val busy = Recording.busy || stage is Stage.Failed
    when {
        busy || screen == Screen.Record -> RecordScreen(onBack = { screen = Screen.Home })
        else -> when (val s = screen) {
            Screen.Home -> HomeScreen(
                onRecord = { screen = Screen.Record },
                onTake = { screen = Screen.Take(it) },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
            is Screen.Take -> TakeScreen(s.id, onBack = { screen = Screen.Home })
            Screen.Record -> Unit
        }
    }
}
