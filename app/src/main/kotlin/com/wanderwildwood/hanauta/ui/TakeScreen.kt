package com.wanderwildwood.hanauta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.audio.Player
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.music.Edits
import com.wanderwildwood.hanauta.music.Grid
import com.wanderwildwood.hanauta.music.Key
import com.wanderwildwood.hanauta.music.NoteNames
import com.wanderwildwood.hanauta.music.PPQ
import com.wanderwildwood.hanauta.music.Quantise
import com.wanderwildwood.hanauta.music.Undo
import kotlin.math.roundToInt

/** Bars to a page of the roll. */
private const val BARS = 2

/** One take: its notes a page of bars at a time, to fix and to send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val takes = remember { Takes.get(context) }
    val prefs = remember { Prefs(context) }
    var take by remember(id) { mutableStateOf(takes.get(id)) }
    val pitch = remember(id) { takes.pitchOf(id) }
    val undo = remember(id) { Undo<Take>() }
    var canUndo by remember { mutableStateOf(false) }
    var selected by remember(id) { mutableIntStateOf(-1) }
    var page by remember(id) { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val playing by Player.playing.collectAsState()
    val delete = rememberArmed()

    DisposableEffect(id) { onDispose { Player.stop() } }
    BackHandler { onBack() }

    val t = take ?: run {
        // Deleted from under the screen, or never there.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    /** A change: kept for Undo, saved at once. */
    fun change(next: Take, select: Int = selected) {
        undo.push(t)
        canUndo = true
        take = next
        takes.save(next)
        selected = select
    }

    fun edit(r: Edits.Result) = change(t.copy(notes = r.notes), r.selected)

    val barTicks = PPQ * t.beatsPerBar
    val pageTicks = barTicks * BARS
    val last = t.notes.maxOfOrNull { it.end } ?: 0
    val pages = maxOf(1, (last + pageTicks - 1) / pageTicks)
    if (page >= pages) page = pages - 1
    val from = page * pageTicks
    val onPage = t.notes.filter { it.end > from && it.start < from + pageTicks }.map { it.pitch }
    val range = PitchRange.around(onPage, t.notes.map { it.pitch }.sorted().getOrNull(t.notes.size / 2) ?: 60)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = t.name, maxLines = 1) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    if (playing) BarButton(Icons.Stop, stringResource(R.string.cd_stop)) { Player.stop() }
                    else BarButton(Icons.Play, stringResource(R.string.cd_play)) {
                        Player.play(t.notes, t.tempo, from, if (prefs.playVoice) takes.wav(t.id) else null, t.beatZero)
                    }
                    BarButton(Icons.Share, stringResource(R.string.cd_share)) { sending = true }
                    BarButton(Icons.More, stringResource(R.string.cd_more)) { more = true }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            TextMMD(
                text = stringResource(R.string.take_settings, Words.key(context, t.key), Words.tempo(context, t.tempo), Words.gridShort(context, t.grid)),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().clickable { more = true }.padding(vertical = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous_bars)) { if (page > 0) { page--; selected = -1 } }
                val firstBar = page * BARS + 1
                val lastBar = minOf(firstBar + BARS - 1, maxOf(1, (last + barTicks - 1) / barTicks))
                val allBars = maxOf(1, (last + barTicks - 1) / barTicks)
                TextMMD(
                    text = if (lastBar > firstBar) stringResource(R.string.take_bars, firstBar, lastBar, allBars)
                    else stringResource(R.string.take_bar, firstBar, allBars),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                BarButton(Icons.ChevronRight, stringResource(R.string.cd_next_bars)) { if (page < pages - 1) { page++; selected = -1 } }
            }
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface)) {
                if (t.notes.isEmpty()) {
                    TextMMD(text = stringResource(R.string.take_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                } else {
                    PianoRoll(
                        notes = t.notes,
                        selected = selected,
                        from = from,
                        ticks = pageTicks,
                        beatsPerBar = t.beatsPerBar,
                        key = t.key,
                        range = range,
                        voice = pitch?.let { VoiceLine(it, t.beatZero, t.tempo, t.tuning) },
                        onTap = { selected = it },
                        // A turned page lets go of the chosen note, so nothing changes a note out of sight.
                        onPage = { d ->
                            val next = (page + d).coerceIn(0, pages - 1)
                            if (next != page) { page = next; selected = -1 }
                        },
                        modifier = Modifier.fillMaxSize().testTag("roll"),
                    )
                }
            }
            val n = t.notes.getOrNull(selected)
            TextMMD(
                text = if (n == null) stringResource(R.string.take_none_selected) else {
                    val (bar, beat) = Words.place(n.start, t.beatsPerBar)
                    stringResource(R.string.take_selected, NoteNames.name(n.pitch, t.key), bar, beat)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (n != null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 6.dp).testTag("selected"),
            )
            val on = n != null
            val g = t.grid.ticks
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool(stringResource(R.string.edit_up), on, "up") { edit(Edits.move(t.notes, selected, 1)) }
                Tool(stringResource(R.string.edit_down), on, "down") { edit(Edits.move(t.notes, selected, -1)) }
                Tool(stringResource(R.string.edit_shorter), on, "shorter") { edit(Edits.lengthen(t.notes, selected, -1, g)) }
                Tool(stringResource(R.string.edit_longer), on, "longer") { edit(Edits.lengthen(t.notes, selected, 1, g)) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool(stringResource(R.string.edit_merge), on && selected < t.notes.size - 1, "merge") { edit(Edits.mergeNext(t.notes, selected)) }
                Tool(stringResource(R.string.edit_split), on && n!!.length >= 2 * g, "split") { edit(Edits.split(t.notes, selected, g)) }
                Tool(stringResource(if (delete.on) R.string.edit_delete_armed else R.string.edit_delete), on, "delete", bold = delete.on) {
                    delete.press { edit(Edits.delete(t.notes, selected)) }
                }
                Tool(stringResource(R.string.edit_undo), canUndo, "undo") {
                    undo.pop()?.let { before ->
                        take = before
                        takes.save(before)
                        selected = selected.coerceAtMost(before.notes.size - 1)
                    }
                    canUndo = undo.canUndo
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (sending) SendDialog(t, onDismiss = { sending = false })
    if (more) TakeDialog(
        t,
        onChange = { change(it, -1) },
        onDelete = {
            Player.stop()
            takes.delete(t.id)
            onBack()
        },
        onDismiss = { more = false },
    )
}

@Composable
private fun RowScope.Tool(text: String, enabled: Boolean, tag: String, bold: Boolean = false, onClick: () -> Unit) {
    OutlinedButtonMMD(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(48.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        TextMMD(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun SendDialog(take: Take, onDismiss: () -> Unit) {
    val context = LocalContext.current
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.send_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        for ((what, title, note) in listOf(
            Triple(Send.What.Midi, R.string.send_midi, R.string.send_midi_note),
            Triple(Send.What.Sheet, R.string.send_musicxml, R.string.send_musicxml_note),
            Triple(Send.What.Recording, R.string.send_recording, R.string.send_recording_note),
        )) {
            Box(Modifier.testTag("send_${what.name}")) {
                SettingRow(stringResource(title), stringResource(note)) {
                    onDismiss()
                    Send.send(context, take, what)
                }
            }
        }
        DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

/** The take's own name, key, grid and tempo, placing again, and deleting. */
@Composable
private fun TakeDialog(take: Take, onChange: (Take) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(take.name) }
    var picking by remember { mutableStateOf<String?>(null) }

    /** The same take with new settings, its notes placed again from the voice. */
    fun replaced(t: Take): Take {
        val p = Quantise.align(t.sung, t.placing)
        return t.copy(beatZero = p.beatZero, notes = Quantise.place(t.sung, p))
    }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.take_more_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Field(stringResource(R.string.take_rename), name, { name = it.replace('\n', ' ').take(80) })
        if (name.isNotBlank() && name.trim() != take.name) {
            DialogButton(stringResource(R.string.save), filled = true) { onChange(take.copy(name = name.trim())) }
        }
        SettingRow(stringResource(R.string.take_key), Words.key(context, take.key)) { picking = "key" }
        SettingRow(stringResource(R.string.take_grid), Words.grid(context, take.grid)) { picking = "grid" }
        SettingRow(
            stringResource(R.string.take_tempo),
            if (take.clicked) Words.tempo(context, take.tempo) else stringResource(R.string.take_tempo_guessed, Words.tempo(context, take.tempo)),
        ) { picking = "tempo" }
        ArmedRow(stringResource(R.string.take_replace), stringResource(R.string.take_replace_armed)) {
            onChange(replaced(take))
            onDismiss()
        }
        ArmedRow(stringResource(R.string.take_delete), stringResource(R.string.take_delete_armed)) { onDelete() }
        DialogButton(stringResource(R.string.close), onClick = onDismiss)
    }

    when (picking) {
        "key" -> KeyDialog(take.key, onPick = { onChange(replaced(take.copy(key = it))); picking = null }, onDismiss = { picking = null })
        "grid" -> OptionsDialog(
            title = stringResource(R.string.take_grid),
            options = Grid.entries.map { it to Words.grid(context, it) },
            chosen = take.grid,
            onPick = { onChange(replaced(take.copy(grid = it))) },
            onDismiss = { picking = null },
        )
        "tempo" -> TempoDialog(take.tempo, onPick = { onChange(replaced(take.copy(tempo = it))); picking = null }, onDismiss = { picking = null })
    }
}

/** Any note, or one of the twenty-four keys, each a stone: black when it is the one. */
@Composable
private fun KeyDialog(chosen: Key, onPick: (Key) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.take_key), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        ChoiceRow(stringResource(R.string.key_chromatic), chosen.chromatic) { onPick(Key.Chromatic) }
        for (minor in listOf(false, true)) {
            Heading(stringResource(if (minor) R.string.key_minor_heading else R.string.key_major_heading))
            for (row in 0 until 3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        // Round the circle of fifths, so neighbours on the page are neighbours in sound.
                        val tonic = ((row * 4 + c) * 7 + if (minor) 9 else 0) % 12
                        val key = Key(tonic, minor)
                        val on = key == chosen
                        val label = Words.key(context, key).substringBefore(' ')
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .padding(vertical = 3.dp)
                                .let { if (on) it.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)) else it.border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface), RoundedCornerShape(4.dp)) }
                                .clickable { onPick(key) }
                                .testTag("key_${tonic}_${if (minor) "m" else "M"}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextMMD(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

@Composable
private fun TempoDialog(tempo: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(tempo.roundToInt().toString()) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.take_tempo), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Field(stringResource(R.string.take_tempo), value, { value = it.filter(Char::isDigit).take(3) }, number = true)
        val v = value.toIntOrNull()
        DialogButton(stringResource(R.string.save), filled = true) {
            if (v != null) onPick(v.coerceIn(Prefs.MIN_TEMPO, Prefs.MAX_TEMPO).toFloat())
        }
        DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}
