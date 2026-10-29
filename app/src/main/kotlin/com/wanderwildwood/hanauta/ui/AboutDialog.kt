package com.wanderwildwood.hanauta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.hanauta.BuildConfig
import com.wanderwildwood.hanauta.R

/** What this is, what it does with what you record, its licence, and where its source is. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    // The words run to more than a dialog holds, so they turn over by whole rows like any list.
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        PagedColumn(Modifier.fillMaxWidth().height(height)) {
            item { Paragraph(R.string.about_what) }
            item { Heading(stringResource(R.string.about_privacy_heading)) }
            item { Paragraph(R.string.about_privacy) }
            item { Heading(stringResource(R.string.about_terms_heading)) }
            item { Paragraph(R.string.about_terms) }
            item { LicencesLink() }
            item { Llama() }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Paragraph(text: Int) {
    TextMMD(text = stringResource(text), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
}

/** A line that opens a web address when pressed, a target of at least 48 dp. */
@Composable
private fun LinkLine(text: Int, url: String) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open(context, url) },
        contentAlignment = Alignment.CenterStart,
    ) {
        TextMMD(text = stringResource(text), style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline))
    }
}

private fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to. The site's
 * address sits at the start of the same line and opens the site; the llama and its words
 * open the page.
 *
 * Straight to the checkout: the Donate button on the site only leads there anyway. The short
 * square.link form, which is what the site itself links to, so a regenerated checkout follows
 * it. A phone with nothing that opens a web address says so rather than doing nothing.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextMMD(
            text = "wanderthe.dev",
            style = MaterialTheme.typography.labelSmall,
            // The site's address opens the site, the way the llama beside it opens its page.
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { open(context, "https://wanderthe.dev") }
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { open(context, "https://square.link/u/AGu8oT10") },
        ) {
            Image(
                painter = painterResource(R.drawable.llama),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextMMD(text = stringResource(R.string.about_feed_the_llamas), style = MaterialTheme.typography.labelSmall)
        }
    }
}
