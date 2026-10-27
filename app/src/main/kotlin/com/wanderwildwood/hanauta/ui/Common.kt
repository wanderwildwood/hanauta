package com.wanderwildwood.hanauta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import kotlinx.coroutines.delay

/** An icon in a top bar, with a target a thumb can find. */
@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A row that says what it is and what it is set to, and does something when pressed. */
@Composable
fun SettingRow(title: String, value: String?, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        if (value != null) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
            if (note != null) TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        SwitchMMD(checked = checked, onCheckedChange = null)
    }
}

/** One of a few choices, pressed in place: MMD's radio button, the row taking the press. */
@Composable
fun ChoiceRow(title: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButtonMMD(selected = chosen, onClick = null)
        Spacer(Modifier.width(8.dp))
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A press that asks once, in its own face: the first press arms it and changes what it says,
 * the second does it. It disarms itself after six seconds, so a stray press does not leave
 * a live trigger for whoever picks the phone up next.
 */
@Composable
fun rememberArmed(): Armed {
    val a = remember { Armed() }
    LaunchedEffect(a.on) {
        if (!a.on) return@LaunchedEffect
        delay(ARMED_MILLIS)
        a.on = false
    }
    return a
}

/** Long enough to read the new wording and press again with a slow thumb. */
private const val ARMED_MILLIS = 6000L

class Armed {
    var on by mutableStateOf(false)

    /** Arms on the first press, and runs [action] on the second. */
    fun press(action: () -> Unit) {
        if (on) {
            on = false
            action()
        } else {
            on = true
        }
    }
}

@Composable
fun ArmedRow(label: String, armedLabel: String, onConfirmed: () -> Unit) {
    val armed = rememberArmed()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { armed.press(onConfirmed) }
            .padding(vertical = 14.dp),
    ) {
        TextMMD(
            text = if (armed.on) armedLabel else label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (armed.on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A small heading over a group of rows. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

/** A choice of one, in a dialog. */
@Composable
fun <T> OptionsDialog(title: String, options: List<Pair<T, String>>, chosen: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        for ((value, label) in options) {
            ChoiceRow(label, value == chosen) {
                onPick(value)
                onDismiss()
            }
        }
    }
}

/** A full-width button inside a dialog. */
@Composable
fun DialogButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(48.dp)
    if (filled) {
        ButtonMMD(onClick = onClick, modifier = modifier) { TextMMD(text = text, style = MaterialTheme.typography.bodyMedium) }
    } else {
        OutlinedButtonMMD(onClick = onClick, modifier = modifier) { TextMMD(text = text, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A text field with its label over it, the way a form reads on paper. */
@Composable
fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String? = null,
    number: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        // A blinking cursor repaints the field twice a second for as long as it has focus, which on
        // an E Ink screen is a refresh nobody asked for; a steady one costs nothing at rest.
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
        TextFieldMMD(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            placeholder = hint?.let { { TextMMD(text = it, style = MaterialTheme.typography.labelSmall) } },
            keyboardOptions = KeyboardOptions(
                capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth().textActions(),
        )
        }
    }
}
