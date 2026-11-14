package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.BrowserVisit
import de.kjell.zencompanion.data.searchVisits
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.sheets.ZenSheet
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.ui.theme.liftRim
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

/**
 * The pages opened in this browser, newest first and grouped by day. The same
 * card look as the Settings and Activity sheets: a quiet day label over one
 * lift card, favicons, app colours only.
 */
@Composable
internal fun BrowserHistoryScreen(
    onOpen: (BrowserVisit) -> Unit,
    onClose: () -> Unit,
) {
    val zen = LocalZenColors.current
    val visits by BrowserHistory.visits.collectAsState()
    var query by remember { mutableStateOf("") }
    val found = remember(visits, query) { searchVisits(visits, query) }
    val days = remember(found) {
        // One calendar for the whole grouping: `dayOf` resets it on every call.
        val calendar = Calendar.getInstance()
        found.groupBy { dayOf(it.lastVisited, calendar) }.toList()
    }
    val dayFormat = remember { DateFormat.getDateInstance(DateFormat.FULL) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ZenSheet(onDismiss = onClose, skipPartiallyExpanded = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(zen.background)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = ZenType.rounded(20, FontWeight.SemiBold),
                    color = zen.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = zen.ink,
                    )
                }
            }

            // Filled field, app-toned instead of Material outline.
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = zen.ink.copy(alpha = 0.5f))
                },
                placeholder = {
                    Text(
                        text = stringResource(R.string.history_search),
                        style = ZenType.rounded(15, FontWeight.Normal),
                        color = zen.ink.copy(alpha = 0.4f),
                    )
                },
                textStyle = ZenType.rounded(15, FontWeight.Normal),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = zen.lift,
                    unfocusedContainerColor = zen.lift,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = zen.coral,
                    focusedTextColor = zen.ink,
                    unfocusedTextColor = zen.ink,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .border(liftBorder(), RoundedCornerShape(16.dp)),
            )

            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.history_empty else R.string.history_no_results,
                        ),
                        style = ZenType.rounded(15, FontWeight.Medium),
                        color = zen.ink.copy(alpha = 0.55f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 6.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    days.forEachIndexed { dayIndex, (day, group) ->
                        // One item per label and one per row: a day of history
                        // only composes as far as it is scrolled to.
                        item(key = DAY_KEY + day) {
                            DayLabel(
                                label = dayFormat.format(Date(day)),
                                spaced = dayIndex > 0,
                            )
                        }
                        itemsIndexed(group, key = { _, visit -> visit.url }) { index, visit ->
                            DayRow(
                                visit = visit,
                                time = timeFormat.format(Date(visit.lastVisited)),
                                first = index == 0,
                                last = index == group.lastIndex,
                                onOpen = { onOpen(visit) },
                                onDelete = { BrowserHistory.remove(visit) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Day headers are keyed by their day value, which no URL can equal. */
private const val DAY_KEY = "history-day-"

/** One day's label above its card; [spaced] keeps the gap between days. */
@Composable
private fun DayLabel(label: String, spaced: Boolean) {
    val zen = LocalZenColors.current
    Text(
        text = label,
        style = ZenType.rounded(12, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
        color = zen.ink.copy(alpha = 0.4f),
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (spaced) 22.dp else 0.dp,
            bottom = 8.dp,
        ),
    )
}

/**
 * One page in a day's card. The card's own round corners sit on the day's
 * first and last row, so separate items still read as one card. Its own key
 * is the URL, so a deleted entry doesn't hand its swipe state to the row that
 * takes its place.
 */
@Composable
private fun DayRow(
    visit: BrowserVisit,
    time: String,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val zen = LocalZenColors.current
    val shape = RoundedCornerShape(
        topStart = if (first) 16.dp else 0.dp,
        topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp,
        bottomEnd = if (last) 16.dp else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(zen.lift)
            .liftRim(shape),
    ) {
        VisitRow(
            visit = visit,
            time = time,
            onOpen = onOpen,
            onDelete = onDelete,
        )
        if (!last) {
            HorizontalDivider(
                color = zen.ink.copy(alpha = 0.08f),
                modifier = Modifier.padding(start = 54.dp),
            )
        }
    }
}

/**
 * One page in the day's card. Swiping it to the side deletes it, the way a
 * list on this platform does.
 */
@Composable
private fun VisitRow(visit: BrowserVisit, time: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    val zen = LocalZenColors.current
    val deleteLabel = stringResource(R.string.history_delete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The card's own fill, opaque: `lift` alone is translucent, so
                // the swipe background would show through the row at rest.
                .background(zen.background)
                .background(zen.lift)
                .clickable(onClick = onOpen)
                // A swipe alone would leave screen readers no way to delete.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDelete()
                            true
                        },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Favicon(urlString = visit.url, size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = visit.title.ifEmpty { visit.host },
                    style = ZenType.rounded(15, FontWeight.Medium),
                    color = zen.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "$time · ${visit.displayAddress}",
                    style = ZenType.rounded(12, FontWeight.Normal),
                    color = zen.ink.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
