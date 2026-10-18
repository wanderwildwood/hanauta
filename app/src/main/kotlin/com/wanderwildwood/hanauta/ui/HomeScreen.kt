package com.wanderwildwood.hanauta.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.audio.Recording
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.data.Takes

/** The Record button, and every take, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRecord: () -> Unit, onTake: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val takes = remember { Takes.get(context) }
    val version by takes.version.collectAsState()
    val all = remember(version) { takes.all() }
    val prefs = remember { Prefs(context) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Recording.import(context, uri, null, prefs.beatsPerBar)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = { BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings) },
            )
        },
    ) { padding ->
        val state = rememberLazyListState()
        ListJumpBox(state, ListJump.Pages, Modifier.padding(padding)) {
            PagedColumn(Modifier.fillMaxSize(), state = state) {
                item(key = "record") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        ButtonMMD(
                            onClick = onRecord,
                            modifier = Modifier.fillMaxWidth().height(64.dp).testTag("record"),
                        ) {
                            Icon(Icons.Mic, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            TextMMD(text = stringResource(R.string.record), style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        TextMMD(
                            text = if (prefs.click) stringResource(R.string.home_click_on, prefs.tempo, prefs.beatsPerBar)
                            else stringResource(R.string.home_click_off),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                item(key = "import") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { import.launch(arrayOf("audio/*")) }
                            .testTag("import")
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.home_import), style = MaterialTheme.typography.bodyMedium)
                        TextMMD(text = stringResource(R.string.home_import_note), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDividerMMD(thickness = 1.dp)
                }
                if (all.isEmpty()) {
                    item(key = "empty") {
                        TextMMD(
                            text = stringResource(R.string.home_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                for (t in all) {
                    item(key = t.id) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onTake(t.id) }
                                .testTag("take_${t.id}")
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                TextMMD(text = t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                TextMMD(
                                    text = stringResource(
                                        R.string.take_line,
                                        Words.duration(t.seconds),
                                        pluralStringResource(R.plurals.notes_count, t.notes.size, t.notes.size),
                                        Words.key(context, t.key),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        HorizontalDividerMMD(thickness = 1.dp)
                    }
                }
            }
        }
    }
}
