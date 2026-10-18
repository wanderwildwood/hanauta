package com.wanderwildwood.hanauta

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.hanauta.audio.Recording
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.ui.HumApp
import com.wanderwildwood.hanauta.ui.monochrome
import java.io.File

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) debugImport()
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                // A debug build names its rows for the emulator tests; a release names none.
                Box(Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }) {
                    HumApp()
                }
            }
        }
    }

    /**
     * Debug builds only: a sound file named in the launching intent, read from the app's own
     * files as a take, so an emulator with no voice to give can be tested with a known tune.
     * `BuildConfig.DEBUG` is false in a release, so this is compiled out of it.
     */
    private fun debugImport() {
        if (!BuildConfig.DEBUG) return
        val name = intent?.getStringExtra("import") ?: return
        val file = File(filesDir, name)
        if (file.isFile) Recording.import(this, Uri.fromFile(file), file.nameWithoutExtension, Prefs(this).beatsPerBar)
    }
}
