package de.kjell.zencompanion.ui.sheets

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.favicon.FaviconResolver
import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.ui.ActivityState
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.theme.LocalZenColors
import java.net.URL
import java.util.Date

/**
 * Port of `SyncedActivitySheet` (iOS): read-only view over the synced
 * browsing history. Nothing here writes to sync. Card design mirrors the
 * Settings sheet: `ZenColors.lift` cards with rounded corners on the paper
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
                .background(colors.paper)
                .statusBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
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
                    .padding(horizontal = 20.dp),
            )

            when {
                state.loading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = colors.coral)
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
                    Button(onClick = onLoad) {
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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        if (history.isNotEmpty()) {
            item(key = "h-history") {
                SectionHeader(stringResource(R.string.activity_section_history))
            }
            // Card top cap
            item(key = "history-card-top") {
                Box(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(colors.lift),
                )
            }
            // One lazy item per row: a 400-entry history only composes what
            // is on screen instead of one giant card item.
            history.forEachIndexed { index, entry ->
                item(key = "hist-row-$index") {
                    Column(
                        Modifier
                            .padding(horizontal = 20.dp)
                            .background(colors.lift),
                    ) {
                        HistoryRow(entry = entry, onOpenUrl = onOpenUrl)
                        if (index < history.size - 1) {
                            HorizontalDivider(
                                color = colors.ink.copy(alpha = 0.08f),
                                modifier = Modifier.padding(start = 52.dp),
                            )
                        }
                    }
                }
            }
            // Card bottom cap
            item(key = "history-card-bottom") {
                Box(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(colors.lift),
                )
            }
            item(key = "history-footer") {
                Text(
                    stringResource(R.string.activity_history_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink.copy(alpha = 0.35f),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.activity_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 48.dp),
                )
            }
        }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(entry.url, entry.title.ifEmpty { null }) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title.ifEmpty { entry.url },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = historySecondary(entry),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
