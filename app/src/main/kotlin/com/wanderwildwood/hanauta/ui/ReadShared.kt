package com.wanderwildwood.hanauta.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.hanauta.R

/**
 * "Read <name>?" for a sound file another app shared. Any app can share one, so nothing of it
 * is read until Read is pressed here; Cancel leaves it unread. While [waiting] (a take is being
 * made or read, or a backup restored) Read waits too, and the dialog says why.
 */
@Composable
fun ReadSharedDialog(name: String?, waiting: Boolean, onRead: () -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = if (name != null) stringResource(R.string.shared_read_title, name) else stringResource(R.string.shared_read_title_unnamed),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = stringResource(if (waiting) R.string.shared_read_wait else R.string.shared_read_note),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        DialogButtons(
            cancel = stringResource(R.string.cancel),
            confirm = stringResource(R.string.shared_read),
            onCancel = onDismiss,
            onConfirm = onRead,
            modifier = Modifier.testTag("shared_read"),
            confirmOn = true,
            confirmEnabled = !waiting,
        )
    }
}
