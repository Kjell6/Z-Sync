package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.BrowserVisit
import de.kjell.zencompanion.data.searchVisits
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** The start of the day a visit fell on, for grouping the list. */
internal fun dayOf(time: Long, calendar: Calendar = Calendar.getInstance()): Long {
    calendar.timeInMillis = time
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** The pages opened in this browser, newest first and grouped by day. */
@Composable
internal fun BrowserHistoryScreen(
    onOpen: (BrowserVisit) -> Unit,
    onClose: () -> Unit,
) {
    val zen = LocalZenColors.current
    val visits by BrowserHistory.visits.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val found = remember(visits, query) { searchVisits(visits, query) }
    val days = remember(found) { found.groupBy { dayOf(it.lastVisited) } }
    val dayFormat = remember { DateFormat.getDateInstance(DateFormat.FULL) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = ZenType.rounded(20, FontWeight.SemiBold),
                    color = zen.ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = visits.isNotEmpty(), onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.history_clear))
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = zen.ink,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.history_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.history_empty else R.string.history_no_results,
                        ),
                        style = ZenType.rounded(16, FontWeight.Normal),
                        color = zen.ink.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    days.forEach { (day, group) ->
                        item(key = day) {
                            Text(
                                text = dayFormat.format(Date(day)),
                                style = ZenType.rounded(13, FontWeight.Medium),
                                color = zen.ink.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group, key = { it.url }) { visit ->
                            VisitRow(
                                visit = visit,
                                time = timeFormat.format(Date(visit.lastVisited)),
                                onOpen = { onOpen(visit) },
                                onDelete = { BrowserHistory.remove(visit) },
                            )
                        }
                    }
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.history_clear_title)) },
                text = { Text(stringResource(R.string.history_clear_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        BrowserHistory.clear()
                    }) { Text(stringResource(R.string.history_clear)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun VisitRow(visit: BrowserVisit, time: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    val zen = LocalZenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = visit.title.ifEmpty { visit.host },
                style = ZenType.rounded(16, FontWeight.Medium),
                color = zen.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$time · ${visit.displayAddress}",
                style = ZenType.rounded(13, FontWeight.Normal),
                color = zen.ink.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.history_delete),
                tint = zen.ink.copy(alpha = 0.5f),
            )
        }
    }
}
