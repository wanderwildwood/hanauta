package com.wanderwildwood.hanauta

import com.wanderwildwood.hanauta.ui.DarkSelection
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.hanauta.audio.Recording
import com.wanderwildwood.hanauta.audio.Recover
import com.wanderwildwood.hanauta.ui.Send
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.ui.ArmedScope
import com.wanderwildwood.hanauta.ui.HumApp
import com.wanderwildwood.hanauta.ui.Notice
import com.wanderwildwood.hanauta.ui.NoticeStrip
import com.wanderwildwood.hanauta.ui.ReadSharedDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.wanderwildwood.hanauta.audio.Stage
import com.wanderwildwood.hanauta.ui.onEachPress
import com.wanderwildwood.hanauta.ui.houseTypography
import com.wanderwildwood.hanauta.ui.monochrome
import java.io.File

class MainActivity : ComponentActivity() {
    private val turner = TurnWithPhone(this)
    private val prefs by lazy { Prefs(this) }
    // Held here: the preferences keep only a weak hold on it.
    private var turnListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * A sound file shared from another app, waiting for "Read <name>?" to be answered. Nothing
     * of it is read before Read is pressed but its name. Kept across a window built again.
     */
    private val askShared = mutableStateOf<SharedSound?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Copies left by an earlier run: files sent long ago, and a shared file whose read
            // never finished. Not while a read is under way, which may be using one.
            if (!Recording.busy) {
                Send.clearOld(this)
                Recording.inbox(this).listFiles()?.forEach { it.delete() }
                // Takes cut off before their notes were found are read for them now.
                Recover.start(this)
            }
            debugImport()
            readShared(intent)
        } else {
            askShared.value = savedInstanceState.getString(STATE_SHARED_URI)?.let {
                SharedSound(Uri.parse(it), savedInstanceState.getString(STATE_SHARED_NAME))
            }
        }
        turner.set(prefs.turnWithPhone)
        // The switch in Settings takes effect at once.
        turnListener = prefs.onTurnChanged { turner.set(it) }
        setContent {
            ThemeMMD(colorScheme = monochrome, typography = houseTypography) { DarkSelection {
                ArmedScope {
                    // A debug build names its rows for the emulator tests; a release names none.
                    // A notice along the foot stays until the next press (see Notice).
                    Box(
                        Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }
                            .onEachPress { Notice.clear() },
                    ) {
                        HumApp()
                        NoticeStrip(Modifier.align(Alignment.BottomCenter))
                    }
                    askShared.value?.let { shared ->
                        val stage by Recording.now.collectAsState()
                        val restoring by Recording.restoringNow.collectAsState()
                        val waiting = restoring ||
                            stage.let { it is Stage.CountIn || it is Stage.Listening || it is Stage.Finding }
                        ReadSharedDialog(
                            name = shared.name,
                            waiting = waiting,
                            onRead = {
                                // Kept asking while a take is under way, rather than closed with
                                // nothing read.
                                if (Recording.importShared(this, shared.uri, prefs.beatsPerBar)) {
                                    askShared.value = null
                                }
                            },
                            onDismiss = { askShared.value = null },
                        )
                    }
                }
            } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShared(intent)
    }

    /**
     * A sound file shared to Hum from another app: asked about first ("Read <name>?"), then read
     * in as "Read a recording" would, and the take opens when its notes are found. Coming back to
     * the app from Recents brings the same share again; it is not asked about or read twice.
     */
    private fun readShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return
        // Only a file another app lends: never a path into this app's own storage.
        if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) return
        askShared.value = SharedSound(uri, Recording.sharedName(this, uri))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        askShared.value?.let {
            outState.putString(STATE_SHARED_URI, it.uri.toString())
            outState.putString(STATE_SHARED_NAME, it.name)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        Notice.pressed(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        turner.resume()
    }

    override fun onPause() {
        turner.pause()
        super.onPause()
    }

    override fun onDestroy() {
        turnListener?.let(prefs::stopWatching)
        super.onDestroy()
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

/** A shared sound file's address, and its name without the ending when the sender gives one. */
private data class SharedSound(val uri: Uri, val name: String?)

private const val STATE_SHARED_URI = "shared_uri"
private const val STATE_SHARED_NAME = "shared_name"
