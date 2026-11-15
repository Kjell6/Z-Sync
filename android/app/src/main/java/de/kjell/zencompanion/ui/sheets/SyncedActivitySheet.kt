package de.kjell.zencompanion.ui.sheets

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.favicon.FaviconResolver
import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.ui.ActivityState
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenLoadingIndicator
import de.kjell.zencompanion.ui.theme.liftBorder
import java.net.URL
import java.util.Date

/**
 * Port of `SyncedActivitySheet` (iOS): read-only view over the synced
 * browsing history. Nothing here writes to sync. Card design mirrors the
 * Settings sheet: grouped Material 3 lists with rounded corners on the paper
 * background, app colors only.
 */
@Composable
fun SyncedActivitySheet(
    state: ActivityState,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: (url: String, title: String?) -> Unit,
) {
    val colors = LocalZenColors.current

    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onLoad() }

    ZenSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            // Title bar. The sheet's own drag handle and status-bar inset sit
            // above this, so only a normal row inset is needed here.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = SheetContentTopSpacing, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = colors.ink,
                    )
                }
            }

            // Search field (filled, app-toned instead of Material outline)
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.ink.copy(alpha = 0.5f))
                },
                placeholder = {
                    Text(
                        stringResource(R.string.activity_search_prompt),
                        color = colors.ink.copy(alpha = 0.4f),
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.lift,
                    unfocusedContainerColor = colors.lift,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = colors.coral,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .border(liftBorder(), RoundedCornerShape(16.dp)),
            )

            when {
                state.loading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ZenLoadingIndicator(color = colors.coral)
                    Text(
                        stringResource(R.string.activity_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink.copy(alpha = 0.6f),
                    )
                }

                state.errorRes != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = colors.ink.copy(alpha = 0.35f),
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        stringResource(state.errorRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onLoad, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.spaces_retry))
                    }
                }

                else -> ActivityList(
                    activity = state.activity,
                    query = query.trim(),
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

@Composable
private fun ActivityList(
    activity: SyncedActivityService.Activity?,
    query: String,
    onOpenUrl: (String, String?) -> Unit,
) {
    val history = remember(activity, query) { filterHistory(activity?.history.orEmpty(), query) }
    val colors = LocalZenColors.current

    if (history.isEmpty()) {
        Text(
            stringResource(R.string.activity_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp),
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(stringResource(R.string.activity_section_history))

        // One grouped list for the whole history, so the card chrome (fill +
        // rim + corner) matches the Settings sheet.
        ZenListGroup(
            modifier = Modifier
                .fillMaxWidth()
                // The card hugs a short history and only fills the sheet when
                // the list is long enough to scroll.
                .weight(1f, fill = false)
                .padding(horizontal = 20.dp),
        ) {
            // One lazy item per row: a 400-entry history only composes what
            // is on screen instead of one giant card item.
            LazyColumn {
                itemsIndexed(history) { _, entry ->
                    HistoryRow(entry = entry, onOpenUrl = onOpenUrl)
                }
            }
        }

        Text(
            stringResource(R.string.activity_history_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink.copy(alpha = 0.35f),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    val colors = LocalZenColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.ink.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun HistoryRow(
    entry: SyncedActivityService.HistoryEntry,
    onOpenUrl: (String, String?) -> Unit,
) {
    val colors = LocalZenColors.current
    ZenListItem(
        headline = entry.title.ifEmpty { entry.url },
        supportingText = historySecondary(entry),
        singleLine = true,
        onClick = { onOpenUrl(entry.url, entry.title.ifEmpty { null }) },
        leadingContent = {
            if (FaviconResolver.isLocalURL(entry.url)) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = colors.ink.copy(alpha = 0.55f),
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Favicon(urlString = entry.url, size = 24.dp)
            }
        },
    )
}

// MARK: - Helpers

private fun historySecondary(entry: SyncedActivityService.HistoryEntry): String =
    listOfNotNull(
        runCatching { URL(entry.url).host }.getOrNull(),
        entry.lastVisit?.let { relative(it) },
    ).joinToString(" · ")

private fun relative(date: Date): String =
    DateUtils.getRelativeTimeSpanString(
        date.time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

internal fun filterHistory(
    entries: List<SyncedActivityService.HistoryEntry>,
    query: String,
): List<SyncedActivityService.HistoryEntry> {
    if (query.isEmpty()) return entries
    return entries.filter {
        it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
    }
}
