package de.kjell.zencompanion.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces

internal fun displayTitle(tab: ZenSpaces.ZenTab): String {
    val raw = tab.title.trim()
    if (raw.isNotEmpty()) return raw
    val host = runCatching { Uri.parse(tab.url).host }.getOrNull() ?: ""
    return host.ifEmpty { tab.url }
}

/**
 * Native Material 3 Split Row.
 * One row whose width is divided into equal panes — one per split member —
 * each individually tappable. A vertical divider separates the panes; the
 * long titles fade out at their right edge instead of ellipsizing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitRow(
    split: ZenSpaces.ZenSplit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onOpenUrl: ((String, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val deleteText = stringResource(R.string.tabs_unsplit)

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = { menuOpen = true },
            )
            .semantics {
                customActions = listOf(CustomAccessibilityAction(deleteText) { onDelete(); true })
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // Horizontal inset comes from the caller (20dp), matching the
                // space header; no extra internal padding.
                .padding(vertical = 9.dp),
        ) {
            split.tabs.forEachIndexed { index, tab ->
                if (index > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .height(24.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                SplitCell(
                    tab = tab,
                    modifier = Modifier.weight(1f),
                    onOpenUrl = { url, title ->
                        if (onOpenUrl != null) onOpenUrl(url, title) else openURLExternally(context, url)
                    },
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        deleteText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = SplitRowGlyph,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** One equal-width pane inside a split row. Sizes mirror a normal `TabRow`. */
@Composable
private fun SplitCell(
    tab: ZenSpaces.ZenTab,
    modifier: Modifier = Modifier,
    onOpenUrl: (String, String?) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onOpenUrl(tab.url, tab.title) },
    ) {
        ZenTabIcon(tab = tab, size = 28.dp)

        Spacer(Modifier.width(12.dp))

        FadeOutTitle(
            text = displayTitle(tab),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Single-line title truncated with an ellipsis when it overflows, exactly
 *  like a regular `TabRow`. */
@Composable
private fun FadeOutTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Small two-pane glyph used for the "unsplit" action. */
private val SplitRowGlyph: ImageVector
    get() = ImageVector.Builder(
        name = "SplitRowGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Left pane
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 7f)
            lineTo(11f, 7f)
            lineTo(11f, 17f)
            lineTo(4f, 17f)
            close()
        }
        // Right pane
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(13f, 7f)
            lineTo(20f, 7f)
            lineTo(20f, 17f)
            lineTo(13f, 17f)
            close()
        }
    }.build()

/**
 * Native Material 3 Tab Row.
 * Interactive row with favicon, title, and long-press delete menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabRow(
    tab: ZenSpaces.ZenTab,
    deletable: Boolean,
    modifier: Modifier = Modifier,
    deleting: Boolean = false,
    onDelete: () -> Unit = {},
    onOpenUrl: ((String, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val deleteText = stringResource(R.string.tabs_delete)
    val titleText = displayTitle(tab)

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (onOpenUrl != null) {
                        onOpenUrl(tab.url, tab.title)
                    } else {
                        openURLExternally(context, tab.url)
                    }
                },
                onLongClick = if (deletable) ({ menuOpen = true }) else null,
            )
            .semantics {
                if (deletable) {
                    customActions = listOf(CustomAccessibilityAction(deleteText) { onDelete(); true })
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // Horizontal inset comes from the caller (20dp), matching the
                // space header; no extra internal padding.
                .padding(vertical = 9.dp),
        ) {
            ZenTabIcon(tab = tab, size = 28.dp)

            Spacer(Modifier.width(12.dp))

            Text(
                text = titleText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        deleteText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
