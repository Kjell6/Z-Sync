package de.kjell.zencompanion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.OpenPage
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlin.math.abs
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(18.dp)

/**
 * Every open page as a card, full screen over the browser,
 * the way Safari lays out its tabs (port of iOS `OpenPagesOverview`). A card
 * opens with a tap and closes with its X or a swipe; long press has the rest.
 */
@Composable
internal fun OpenPagesOverview(
    pages: BrowserPages,
    openPages: List<OpenPage>,
    activeId: String?,
    spaces: List<ZenSpaces.ZenSpace>,
    /** Folders are offered only when the save lands as a pinned tab. */
    saveKind: SaveKind,
    /** The card a flying picture stands in for right now. */
    hiddenCardId: String?,
    /** Where a card sits in the window, so a page can zoom into it. */
    onCardFrame: (String, Rect) -> Unit,
    onOpen: (OpenPage) -> Unit,
    onNewPage: () -> Unit,
    onDone: () -> Unit,
    /** Done only makes sense with a page behind the overview to go back to. */
    showsDone: Boolean,
    /** Back to the spaces, the same way out as the browser's own close control. */
    onLeave: () -> Unit,
    onPin: (OpenPage, PinDestination) -> Unit,
    onShare: (OpenPage) -> Unit,
) {
    val zen = LocalZenColors.current
    var confirmCloseAll by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState(
        initialFirstVisibleItemIndex = openPages.indexOfFirst { it.id == activeId }.coerceAtLeast(0),
    )

    // The card being dragged to a new place: where it started in the grid, and
    // how far the finger has moved it since.
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    val dragMoved = remember { mutableStateOf(Offset.Zero) }

    fun itemOffset(id: String): Offset? = grid.layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == id }
        ?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(zen.paper)
            // Nothing reaches the browser underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { isTraversalGroup = true }
            .statusBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (openPages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.kept_empty_title),
                        style = ZenType.rounded(20, FontWeight.Medium),
                        color = zen.ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.kept_empty_message),
                        style = ZenType.rounded(15, FontWeight.Normal),
                        color = zen.ink.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    // Two on a phone, as many as fit on a tablet.
                    columns = GridCells.Adaptive(150.dp),
                    state = grid,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(openPages, key = { it.id }) { page ->
                        OpenPageCard(
                            page = page,
                            pages = pages,
                            isCurrent = page.id == activeId,
                            hidesPicture = page.id == hiddenCardId,
                            dragOffset = {
                                if (draggedId == page.id) {
                                    dragStart + dragMoved.value - (itemOffset(page.id) ?: dragStart)
                                } else {
                                    Offset.Zero
                                }
                            },
                            onDragStart = { grab ->
                                draggedId = page.id
                                dragStart = itemOffset(page.id) ?: Offset.Zero
                                dragGrab = grab
                                dragMoved.value = Offset.Zero
                            },
                            onDrag = { amount ->
                                dragMoved.value += amount
                                // Cards make room as the finger passes over them.
                                val finger = dragStart + dragGrab + dragMoved.value
                                grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    item.key != page.id &&
                                        finger.x.toInt() in item.offset.x until item.offset.x + item.size.width &&
                                        finger.y.toInt() in item.offset.y until item.offset.y + item.size.height
                                }?.let { pages.store.move(page.id, it.key as String) }
                            },
                            onDragEnd = { draggedId = null },
                            spaces = spaces,
                            pinsIntoFolders = saveKind == SaveKind.PINNED,
                            onFrame = { onCardFrame(page.id, it) },
                            onOpen = { onOpen(page) },
                            onClose = { pages.close(page.id) },
                            onPin = { onPin(page, it) },
                            onShare = { onShare(page) },
                            modifier = Modifier
                                .zIndex(if (draggedId == page.id) 1f else 0f)
                                .then(
                                    if (draggedId == page.id) {
                                        Modifier.animateItem(placementSpec = null)
                                    } else {
                                        Modifier.animateItem()
                                    },
                                ),
                        )
                    }
                    // A small note under the cards, so nothing closes by surprise.
                    item(key = "lifetime-note", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                        ) {
                            Icon(
                                Icons.Outlined.Inventory2,
                                contentDescription = null,
                                tint = zen.ink.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = stringResource(lifetimeHint(pages.lifetime())),
                                style = ZenType.rounded(12, FontWeight.Normal),
                                color = zen.ink.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        OverviewDock(
            onNewPage = onNewPage,
            onDone = onDone,
            showsDone = showsDone,
            onDoneLongPress = { if (openPages.isNotEmpty()) menuOpen = true },
            onLeave = onLeave,
            doneMenu = {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kept_close_all), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            confirmCloseAll = true
                        },
                    )
                }
            },
        )
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text(stringResource(R.string.kept_close_all_title)) },
            text = { Text(stringResource(R.string.kept_close_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloseAll = false
                    pages.closeAll()
                }) {
                    Text(stringResource(R.string.kept_close_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** One page: its picture with a close button, its name underneath. A sideways swipe throws it away. */
@Composable
private fun OpenPageCard(
    page: OpenPage,
    pages: BrowserPages,
    isCurrent: Boolean,
    hidesPicture: Boolean,
    dragOffset: () -> Offset,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onFrame: (Rect) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = if (page.url.isEmpty()) stringResource(R.string.kept_new_page) else PageTitle.headline(page.title, page.url)
    val currentLabel = stringResource(R.string.kept_current_page)
    val session = remember(page.id) { pages.session(page.id) }
    val drag = remember { Animatable(0f) }
    var cardWidth by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var heldDown by remember { mutableStateOf(false) }

    LaunchedEffect(page.id) { session?.let(pages::loadShot) }

    Column(
        modifier = modifier
            .onSizeChanged { cardWidth = it.width }
            .graphicsLayer {
                val dragged = dragOffset()
                translationX = drag.value + dragged.x
                translationY = dragged.y
                alpha = 1f - (abs(drag.value) / (cardWidth * 1.4f).coerceAtLeast(1f)).coerceAtMost(0.7f)
            }
            // Hold and let go is the menu; hold and move picks the card up.
            .pointerInput(page.id) {
                var travelled = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        travelled = 0f
                        heldDown = true
                        Haptics.perform(context, Haptics.Kind.SELECTION)
                        onDragStart(position)
                    },
                    onDragEnd = onDragEnd,
                    // A finger that stayed put ends here: the card takes the
                    // lift itself, which reads as a cancelled drag.
                    onDragCancel = {
                        onDragEnd()
                        if (travelled < viewConfiguration.touchSlop) menuOpen = true
                    },
                ) { change, amount ->
                    change.consume()
                    travelled += amount.getDistance()
                    onDrag(amount)
                }
            }
            .pointerInput(page.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(drag.value) > cardWidth * 0.45f) {
                                val out = if (drag.value < 0) -cardWidth * 1.5f else cardWidth * 1.5f
                                drag.animateTo(out, tween(180))
                                onClose()
                            } else {
                                drag.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo(drag.value + amount) }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .onGloballyPositioned { onFrame(it.boundsInRoot()) }
                    .alpha(if (hidesPicture) 0f else 1f)
                    .clip(CardShape)
                    .background(zen.lift)
                    // The rim the buttons have: a pale page still ends somewhere.
                    .border(liftBorder(), CardShape)
                    .clickable {
                        // The finger that held the card also lifts here.
                        if (heldDown) heldDown = false else onOpen()
                    }
                    .semantics { if (isCurrent) contentDescription = "$title, $currentLabel" },
                contentAlignment = Alignment.Center,
            ) {
                val shot = session?.shot
                if (shot != null) {
                    Image(
                        bitmap = shot,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (page.url.isNotEmpty()) {
                    Favicon(urlString = page.url, size = 36.dp)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), CircleShape)
                    .barClickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.kept_close_page_named, title),
                    tint = zen.ink.copy(alpha = 0.75f),
                    modifier = Modifier.size(15.dp),
                )
            }
            PageCardMenu(
                expanded = menuOpen,
                page = page,
                canKeep = pages.keepsOnlyChosenPages() &&
                    pages.store.canKeep(page, session?.viewModel?.state?.value?.pageError != null),
                spaces = spaces,
                pinsIntoFolders = pinsIntoFolders,
                onDismiss = { menuOpen = false },
                onToggleKeep = {
                    menuOpen = false
                    session?.let { pages.toggleKeep(it.id) }
                },
                onPin = { destination ->
                    menuOpen = false
                    onPin(destination)
                },
                onCopy = {
                    menuOpen = false
                    copyLink(context, page.url)
                },
                onShare = {
                    menuOpen = false
                    onShare()
                },
                onClose = {
                    menuOpen = false
                    onClose()
                },
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (page.url.isNotEmpty()) Favicon(urlString = page.url, size = 14.dp)
            if (page.isKept && pages.keepsOnlyChosenPages()) {
                Icon(
                    Icons.Outlined.Bookmark,
                    contentDescription = null,
                    tint = zen.ink.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = title,
                style = ZenType.rounded(12, FontWeight.Medium),
                color = zen.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Long press. Pin to space offers each space and, for a pin, its folders. */
@Composable
private fun PageCardMenu(
    expanded: Boolean,
    page: OpenPage,
    canKeep: Boolean,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onDismiss: () -> Unit,
    onToggleKeep: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    var pinning by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (pinning) {
            spaces.forEach { space ->
                DropdownMenuItem(
                    text = { Text(space.name) },
                    onClick = { onPin(PinDestination(spaceId = space.id)) },
                )
                if (pinsIntoFolders) {
                    PinDestinationModel.folderEntries(space).forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.name) },
                            modifier = Modifier.padding(start = 16.dp + 12.dp * folder.depth),
                            onClick = { onPin(PinDestination(spaceId = space.id, folderId = folder.folderId)) },
                        )
                    }
                }
            }
            return@DropdownMenu
        }
        if (canKeep) {
            DropdownMenuItem(
                text = { Text(stringResource(if (page.isKept) R.string.browser_kept else R.string.browser_keep_page)) },
                leadingIcon = {
                    Icon(if (page.isKept) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null)
                },
                onClick = onToggleKeep,
            )
        }
        if (page.url.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kept_pin_to_space)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                enabled = spaces.isNotEmpty(),
                onClick = { pinning = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kept_copy_link)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = onCopy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = onShare,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.kept_close_page), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onClose,
        )
    }
}

@StringRes
private fun lifetimeHint(lifetime: KeptPageLifetime): Int = when (lifetime) {
    KeptPageLifetime.NEVER -> R.string.kept_hint_never
    KeptPageLifetime.DAY -> R.string.kept_hint_day
    KeptPageLifetime.WEEK -> R.string.kept_hint_week
    KeptPageLifetime.MONTH -> R.string.kept_hint_month
}

/** Long press on a link in a page. */
@Composable
internal fun LinkMenu(
    url: String,
    onOpenInNewPage: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenInNewPage, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.browser_open_in_new_page), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.kept_copy_link), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.browser_share), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}

internal fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Haptics.perform(context, Haptics.Kind.SELECTION)
}
