package com.wanderwildwood.hanauta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.music.Grid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var v by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about)) { about = true } },
            )
        },
    ) { padding ->
        val state = rememberLazyListState()
        ListJumpBox(state, ListJump.Pages, Modifier.padding(padding)) {
            PagedColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = state) {
                item { Spacer(Modifier.height(8.dp)) }
                item(key = "grid") {
                    val g = remember(v) { prefs.grid }
                    SettingRow(stringResource(R.string.settings_grid), Words.grid(context, g)) { grid = true }
                }
                item(key = "voice") {
                    val on = remember(v) { prefs.playVoice }
                    SwitchRow(stringResource(R.string.settings_play_voice), on) {
                        prefs.playVoice = it
                        v++
                    }
                }
                item(key = "listjump") {
                    val jumpShown by rememberListJumpShown()
                    SwitchRow(stringResource(R.string.list_jump_setting), jumpShown) { ListJumpPrefs.setShown(context, it) }
                }
            }
        }
    }
    if (grid) {
        OptionsDialog(
            title = stringResource(R.string.settings_grid),
            options = Grid.entries.map { it to Words.grid(context, it) },
            chosen = prefs.grid,
            onPick = { prefs.grid = it; v++ },
            onDismiss = { grid = false },
        )
    }
    if (about) AboutDialog(onDismiss = { about = false })
}
