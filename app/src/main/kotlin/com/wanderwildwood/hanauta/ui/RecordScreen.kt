package com.wanderwildwood.hanauta.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.audio.Reason
import com.wanderwildwood.hanauta.audio.Recording
import com.wanderwildwood.hanauta.audio.Setup
import com.wanderwildwood.hanauta.audio.Stage
import com.wanderwildwood.hanauta.data.Prefs

/** Setting up a take, then the take itself: the count-in, the listening, the finding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val stage by Recording.now.collectAsState()
    val busy = stage.let { it is Stage.CountIn || it is Stage.Listening || it is Stage.Finding }

    // Back while recording stops the take, which is then kept; never a take lost to a stray press.
    BackHandler {
        when (stage) {
            is Stage.CountIn, is Stage.Listening -> Recording.stop()
            is Stage.Finding -> Unit
            is Stage.Failed -> Recording.clear()
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.record_title)) },
                navigationIcon = { if (!busy) BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
        ) {
            when (val s = stage) {
                is Stage.CountIn -> Big(stringResource(R.string.record_count_in), "${s.beat}", stop = true)
                is Stage.Listening -> Big(
                    stringResource(R.string.record_listening),
                    Words.duration(s.seconds.toFloat()),
                    note = stringResource(if (s.loud) R.string.record_hearing else R.string.record_quiet),
                    stop = true,
                )
                // In tens, so the panel is not redrawn for every point.
                is Stage.Finding -> Big(stringResource(R.string.record_finding), "${(s.share * 10).toInt() * 10}%", stop = false)
                is Stage.Failed -> Failed(s.reason)
                else -> SetUp(prefs, onRecord = { Recording.start(context, Setup(prefs.tempo, prefs.beatsPerBar, prefs.click)) })
            }
        }
    }
}

@Composable
private fun ColumnScope.SetUp(prefs: Prefs, onRecord: () -> Unit) {
    val context = LocalContext.current
    var tempo by remember { mutableIntStateOf(prefs.tempo) }
    var beats by remember { mutableIntStateOf(prefs.beatsPerBar) }
    var click by remember { mutableStateOf(prefs.click) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRecord() else refused = true
    }

    Spacer(Modifier.height(12.dp))
    SwitchRow(stringResource(R.string.record_click), click, stringResource(R.string.record_click_note)) {
        click = it
        prefs.click = it
    }
    if (click) {
        Heading(stringResource(R.string.record_tempo))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            StepButton(Icons.Minus, stringResource(R.string.cd_slower), "slower") {
                tempo = (tempo - 5).coerceAtLeast(Prefs.MIN_TEMPO); prefs.tempo = tempo
            }
            TextMMD(
                text = stringResource(R.string.record_bpm, tempo),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            StepButton(Icons.Add, stringResource(R.string.cd_faster), "faster") {
                tempo = (tempo + 5).coerceAtMost(Prefs.MAX_TEMPO); prefs.tempo = tempo
            }
        }
    }
    Heading(stringResource(R.string.record_beats))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (b in listOf(2, 3, 4)) {
            Box(Modifier.weight(1f)) {
                ChoiceRow("$b", beats == b) { beats = b; prefs.beatsPerBar = b }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    if (refused) {
        TextMMD(text = stringResource(R.string.record_permission), style = MaterialTheme.typography.bodyMedium)
        SettingRow(stringResource(R.string.record_permission_settings), null) {
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    ButtonMMD(
        onClick = {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onRecord()
            else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.fillMaxWidth().height(72.dp).testTag("start"),
    ) { TextMMD(text = stringResource(R.string.record_start), style = MaterialTheme.typography.bodyLarge) }
    Spacer(Modifier.height(24.dp))
}

/** What is happening, in large type, and a Stop as big as a thumb could want. */
@Composable
private fun ColumnScope.Big(title: String, figure: String, note: String? = null, stop: Boolean) {
    Spacer(Modifier.height(40.dp))
    TextMMD(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    TextMMD(
        text = figure,
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (note != null) {
        TextMMD(text = note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    Spacer(Modifier.weight(1f))
    if (stop) {
        ButtonMMD(
            onClick = { Recording.stop() },
            modifier = Modifier.fillMaxWidth().height(96.dp).testTag("stop"),
        ) { TextMMD(text = stringResource(R.string.record_stop), style = MaterialTheme.typography.bodyLarge) }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ColumnScope.Failed(reason: Reason) {
    Spacer(Modifier.height(40.dp))
    TextMMD(
        text = stringResource(
            when (reason) {
                Reason.NoMicrophone -> R.string.failed_microphone
                Reason.Unreadable -> R.string.failed_unreadable
                Reason.NoNotes -> R.string.failed_no_notes
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.weight(1f))
    DialogButton(stringResource(R.string.close), filled = true) { Recording.clear() }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.testTag(tag)) { BarButton(icon, description, onClick) }
    Spacer(Modifier.width(4.dp))
}
