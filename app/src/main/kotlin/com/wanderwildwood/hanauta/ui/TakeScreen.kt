package com.wanderwildwood.hanauta.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.res.Configuration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.text.style.TextOverflow
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

/** Bars to a page of the roll, upright; turned sideways the roll is wider and takes twice as many. */
private const val BARS = 2
private const val BARS_WIDE = 4

/** One take: its notes a page of bars at a time, to fix and to send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val takes = remember { Takes.get(context) }
    val prefs = remember { Prefs(context) }
    var take by remember(id) { mutableStateOf(takes.get(id)) }
    val pitch = remember(id) { takes.pitchOf(id) }
    // The steps back outlive a turn of the phone, which builds the screen again; leaving the take lets them go.
    val undo = remember(id) { TurnProof.undo(id) }
    var canUndo by remember { mutableStateOf(undo.canUndo) }
    // Kept through a turn of the phone, which builds the screen again.
    var selected by rememberSaveable(id) { mutableIntStateOf(-1) }
    // Where the page starts, as a bar rather than a page, so a turn that changes the bars to a
    // page keeps the same bars in sight.
    var atBar by rememberSaveable(id) { mutableIntStateOf(0) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playing by Player.playing.collectAsState()
    val position by Player.position.collectAsState()
    val delete = rememberArmed()

    // Leaving the take stops the playing; turning the phone, which builds the screen again, does not.
    DisposableEffect(id) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations != true) {
                Player.stop()
                TurnProof.forget(id)
            }
        }
    }
    BackHandler { onBack() }

    val t = take ?: run {
        // Deleted from under the screen, or never there.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    /** A change: kept for Undo, saved at once. One that changes nothing is not a step to undo. */
    fun change(next: Take, select: Int = selected) {
        if (next == t) {
            selected = select
            return
        }
        undo.push(t)
        canUndo = true
        take = next
        takes.save(next)
        selected = select
    }

    fun edit(r: Edits.Result) = change(t.copy(notes = r.notes), r.selected)

    val barTicks = PPQ * t.beatsPerBar
    val bars = if (wide) BARS_WIDE else BARS
    val pageTicks = barTicks * bars
    val last = t.notes.maxOfOrNull { it.end } ?: 0
    val pages = maxOf(1, (last + pageTicks - 1) / pageTicks)
    val page = (atBar / bars).coerceIn(0, pages - 1)
    /** Turns to page [p], letting go of the chosen note, so nothing changes a note out of sight. */
    fun turnTo(p: Int) {
        val next = p.coerceIn(0, pages - 1)
        if (next != page) { atBar = next * bars; selected = -1 }
    }
    val from = page * pageTicks
    val onPage = t.notes.filter { it.end > from && it.start < from + pageTicks }.map { it.pitch }

    // The playback moves a beat at a time; the page turns to keep it in sight.
    LaunchedEffect(position) {
        if (position >= 0) {
            turnTo(position / pageTicks)
        }
    }
    val range = PitchRange.around(onPage, t.notes.map { it.pitch }.sorted().getOrNull(t.notes.size / 2) ?: 60)

    fun play() = Player.play(t.notes, t.tempo, from, if (prefs.playVoice) takes.wav(t.id) else null, t.beatZero)
    fun undoOne() {
        undo.pop()?.let { before ->
            // A rename is not a step: undoing goes back over the notes and settings only.
            val back = before.copy(name = t.name)
            take = back
            takes.save(back)
            selected = selected.coerceAtMost(before.notes.size - 1)
        }
        canUndo = undo.canUndo
    }

    if (wide) {
        WideTake(
            t = t,
            selected = selected,
            from = from,
            pageTicks = pageTicks,
            page = page,
            pages = pages,
            range = range,
            playing = playing,
            position = position,
            pitch = pitch,
            canUndo = canUndo,
            deleteArmed = delete.on,
            onBack = onBack,
            onRename = { renaming = true },
            onMore = { more = true },
            onSelect = { selected = it },
            onTurn = { turnTo(page + it) },
            onPlay = ::play,
            onSend = { sending = true },
            onUndo = ::undoOne,
            onEdit = ::edit,
            onDelete = { delete.press { edit(Edits.delete(t.notes, selected)) } },
        )
    } else Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    // The name a take starts with is its date; pressing it renames the take.
                    TextMMD(
                        text = t.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable { renaming = true }.testTag("title"),
                    )
                },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    if (playing) BarButton(Icons.Stop, stringResource(R.string.cd_stop)) { Player.stop() }
                    else BarButton(Icons.Play, stringResource(R.string.cd_play)) { play() }
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { more = true }.wrapContentHeight(Alignment.CenterVertically),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous_bars)) { turnTo(page - 1) }
                val firstBar = page * bars + 1
                val lastBar = minOf(firstBar + bars - 1, maxOf(1, (last + barTicks - 1) / barTicks))
                val allBars = maxOf(1, (last + barTicks - 1) / barTicks)
                TextMMD(
                    text = if (lastBar > firstBar) stringResource(R.string.take_bars, firstBar, lastBar, allBars)
                    else stringResource(R.string.take_bar, firstBar, allBars),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                BarButton(Icons.ChevronRight, stringResource(R.string.cd_next_bars)) { turnTo(page + 1) }
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
                        playhead = if (playing) position else -1,
                        voice = pitch?.let { VoiceLine(it, t.beatZero, t.tempo, t.tuning) },
                        onTap = { selected = it },
                        // A turned page lets go of the chosen note, so nothing changes a note out of sight.
                        onPage = { d -> turnTo(page + d) },
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
                Tool(stringResource(R.string.edit_undo), canUndo, "undo") { undoOne() }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (renaming) RenameDialog(
        t.name,
        onSave = { name ->
            // Not through change(): a name is not something Undo takes back.
            val next = t.copy(name = name)
            take = next
            takes.save(next)
            renaming = false
        },
        onDismiss = { renaming = false },
    )
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

/**
 * A take with the phone turned sideways: the roll across the whole width, four bars a page, and
 * one row at the foot. With no note chosen the row plays, stops, sends and undoes, and says where
 * the playing is; with one chosen it holds the tools that change it, and a press beside the notes
 * gives the row back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideTake(
    t: Take,
    selected: Int,
    from: Int,
    pageTicks: Int,
    page: Int,
    pages: Int,
    range: PitchRange,
    playing: Boolean,
    position: Int,
    pitch: com.wanderwildwood.hanauta.music.Track?,
    canUndo: Boolean,
    deleteArmed: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onMore: () -> Unit,
    onSelect: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onPlay: () -> Unit,
    onSend: () -> Unit,
    onUndo: () -> Unit,
    onEdit: (Edits.Result) -> Unit,
    onDelete: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = t.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable(onClick = onRename).testTag("title"),
                    )
                },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = { BarButton(Icons.More, stringResource(R.string.cd_more), onMore) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
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
                        playhead = if (playing) position else -1,
                        voice = pitch?.let { VoiceLine(it, t.beatZero, t.tempo, t.tuning) },
                        onTap = onSelect,
                        onPage = onTurn,
                        modifier = Modifier.fillMaxSize().testTag("roll"),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val n = t.notes.getOrNull(selected)
            val g = t.grid.ticks
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (n != null) {
                    Tool(stringResource(R.string.edit_up), true, "up") { onEdit(Edits.move(t.notes, selected, 1)) }
                    Tool(stringResource(R.string.edit_down), true, "down") { onEdit(Edits.move(t.notes, selected, -1)) }
                    Tool(stringResource(R.string.edit_shorter), true, "shorter") { onEdit(Edits.lengthen(t.notes, selected, -1, g)) }
                    Tool(stringResource(R.string.edit_longer), true, "longer") { onEdit(Edits.lengthen(t.notes, selected, 1, g)) }
                    Tool(stringResource(R.string.edit_merge), selected < t.notes.size - 1, "merge") { onEdit(Edits.mergeNext(t.notes, selected)) }
                    Tool(stringResource(R.string.edit_split), n.length >= 2 * g, "split") { onEdit(Edits.split(t.notes, selected, g)) }
                    Tool(stringResource(if (deleteArmed) R.string.edit_delete_armed else R.string.edit_delete), true, "delete", bold = deleteArmed, onClick = onDelete)
                    Tool(stringResource(R.string.edit_undo), canUndo, "undo", onClick = onUndo)
                } else {
                    Tool(stringResource(R.string.cd_play), !playing, "play", onClick = onPlay)
                    Tool(stringResource(R.string.cd_stop), playing, "stop") { Player.stop() }
                    Tool(stringResource(R.string.save), true, "send", onClick = onSend)
                    Tool(stringResource(R.string.edit_undo), canUndo, "undo", onClick = onUndo)
                    BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous_bars)) { onTurn(-1) }
                    // Where the playing is (or the page starts), of the whole take, and the page.
                    val at = if (playing && position >= 0) position else from
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextMMD(
                            text = stringResource(R.string.take_time, clock(at, t.tempo), clock(t.notes.maxOfOrNull { it.end } ?: 0, t.tempo)),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        TextMMD(text = stringResource(R.string.take_page, page + 1, pages), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    BarButton(Icons.ChevronRight, stringResource(R.string.cd_next_bars)) { onTurn(1) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A place in the take, in ticks, as minutes and seconds at its tempo: "0:08". */
private fun clock(ticks: Int, tempo: Float): String {
    val seconds = (ticks * 60f / (tempo * PPQ)).roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** What a take's screen keeps while the phone turns: its steps back, which cannot be saved in a bundle. */
private object TurnProof {
    private val undos = HashMap<Long, Undo<Take>>()
    fun undo(id: Long): Undo<Take> = undos.getOrPut(id) { Undo() }
    fun forget(id: Long) { undos.remove(id) }
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
    // One file picker for each kind, since a picker is told the type of file up front.
    val savers = Send.What.entries.associateWith { what ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(what.saveMime)) { uri ->
            if (uri != null) Send.save(context, take, what, uri)
            onDismiss()
        }
    }
    val titles = mapOf(
        Send.What.Midi to (R.string.send_midi to R.string.send_midi_note),
        Send.What.Sheet to (R.string.send_musicxml to R.string.send_musicxml_note),
        Send.What.Recording to (R.string.send_recording to R.string.send_recording_note),
    )
    val saveTitles = mapOf(
        Send.What.Midi to R.string.save_as_midi,
        Send.What.Sheet to R.string.save_as_sheet,
        Send.What.Recording to R.string.save_as_recording,
    )
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.send_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Heading(stringResource(R.string.send_to_app))
        for (what in Send.What.entries) {
            val (title, note) = titles.getValue(what)
            Box(Modifier.testTag("send_${what.name}")) {
                SettingRow(stringResource(title), stringResource(note)) {
                    onDismiss()
                    Send.send(context, take, what)
                }
            }
        }
        Heading(stringResource(R.string.send_to_file))
        for (what in Send.What.entries) {
            Box(Modifier.testTag("save_${what.name}")) {
                SettingRow(stringResource(saveTitles.getValue(what)), null) {
                    savers.getValue(what).launch(Send.fileName(take.name) + "." + what.extension)
                }
            }
        }
        DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

/** A take's name, in a small dialog of its own. */
@Composable
private fun RenameDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(name) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.take_rename_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Field(stringResource(R.string.take_rename), value, { value = it.replace('\n', ' ').take(80) }, modifier = Modifier.testTag("name"))
        DialogButton(stringResource(R.string.save), filled = true) {
            if (value.isNotBlank()) onSave(value.trim())
        }
        DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

/** The take's own name, key, grid and tempo, placing again, and deleting. */
@Composable
private fun TakeDialog(take: Take, onChange: (Take) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) }

    /** The same take with new settings, its notes placed again from the voice. */
    fun replaced(t: Take): Take {
        val p = Quantise.align(t.sung, t.placing)
        return t.copy(beatZero = p.beatZero, notes = Quantise.place(t.sung, p))
    }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.take_more_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
                                .height(48.dp)
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
