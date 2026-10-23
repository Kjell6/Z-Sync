package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.ui.theme.pressFeedback

/**
 * The bar sits on the page's own color, nudged away from it so the plate is
 * visible: a touch darker on light colors, a touch lighter on dark ones.
 */
internal fun Color.nudgedForBar(amount: Float = 0.08f): Color {
    val target = if (luminance() < 0.5f) 1f else 0f
    return Color(
        red = red + (target - red) * amount,
        green = green + (target - green) * amount,
        blue = blue + (target - blue) * amount,
        alpha = alpha,
    )
}

/**
 * The app's press feel: shrink and dim while the finger is down, no ripple. The
 * default ripple draws a dark rectangle over the control's square bounds, which
 * does not match the action bar and the tiles.
 */
@Composable
internal fun Modifier.barClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressFeedback(source)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

// MARK: - Metrics

/** A solid surface, not glass: the platform has no Liquid Glass, so the bar is
 * a hard pane that sits on the page. */
private val PanelShape = RoundedCornerShape(20.dp)
private val DockShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(16.dp)

private val BarHeight = 44.dp
private val DockSide = 44.dp
private val PlusWidth = 92.dp
private val PlusHeight = 42.dp
private val RowHeight = 44.dp
private val TileHeight = 60.dp
private val PanelInset = 14.dp
private val RowGap = 8.dp
/** How far the dock drops into the gesture area: the safe area is not touched,
 * only the slack above it is used up. */
private val BottomLift = 15.dp
/** Air above the first row, so the bar background does not end flush with it. */
private val TopGap = 12.dp
/** Pulls the outer dock buttons inwards, like the iOS chrome. */
private val DockInset = 7.dp

/**
 * Bottom browser chrome (port of iOS `MiniBrowserChrome`), bottom to top:
 *
 *   [tab] [＋] [⋯]    three separate buttons; tab and plus are placeholders
 *   [navigation bar]  back, forward, favicon, title, reload
 *   [menu panel]      only while the menu is open: it grows above the bar
 *
 * Unlike iOS the surfaces are solid: no backdrop to blur at the page's edge
 * would read as glass anyway. The bar is wrapped in `ZenTheme(darkTheme =
 * isDark)` by the screen, so every color here follows the page rather than the
 * system theme.
 */
@Composable
internal fun MiniBrowserBar(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    effectiveUrl: String,
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onAddressFocusedChange: (Boolean) -> Unit,
    onBeginAddressEdit: () -> Unit,
    onCancelAddressEdit: () -> Unit,
    onSubmitAddress: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The bar area has its own background already (the page ends above
            // it). Painting it here keeps the translucent tiles compositing
            // against that background instead of the page.
            .background(barColor)
            // The background starts above the address row: it should not end
            // hard against it.
            .padding(start = 16.dp, end = 16.dp, top = TopGap),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        if (state.menuExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pane(PanelShape, barColor)
                    .padding(PanelInset),
            ) {
                ExpandedMenu(
                    state = state,
                    viewModel = viewModel,
                    onCopyLink = onCopyLink,
                    onFind = onFind,
                    onShare = onShare,
                    onOpenExternal = onOpenExternal,
                    onPin = onPin,
                )
            }
        }

        if (state.isAddressFocused) {
            // The bar rides on the keyboard while typing; the dock would only
            // sit under the keys.
            ComposerBar(
                addressText = addressText,
                onAddressTextChange = onAddressTextChange,
                focusRequester = focusRequester,
                onAddressFocusedChange = onAddressFocusedChange,
                onSubmitAddress = onSubmitAddress,
                onCancel = onCancelAddressEdit,
                barColor = barColor,
            )
        } else {
            NavBar(
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                effectiveUrl = effectiveUrl,
                title = state.currentTitle,
                isLoading = state.isLoading,
                progress = state.progress,
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onStop = onStop,
                onBeginAddressEdit = onBeginAddressEdit,
                barColor = barColor,
            )

            Dock(
                menuOpen = state.menuExpanded,
                ink = zen.ink,
                onToggleMenu = {
                    if (state.menuExpanded) viewModel.collapseMenu() else viewModel.toggleMenu()
                },
            )
        }

        val navigationInset = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()
        Spacer(Modifier.height((navigationInset - BottomLift).coerceAtLeast(4.dp)))
    }
}

/**
 * A pane, not a floating sheet: same fill and hairline as the dock buttons, no
 * shadow. Nothing in this chrome hovers over the page.
 */
@Composable
private fun Modifier.pane(shape: Shape, color: Color): Modifier = this
    .clip(shape)
    .background(color)
    .border(liftBorder(), shape)

/**
 * Slim strip above the page: the app's chrome color plus the one explicit way
 * out of the mini-browser (Android has no swipe-to-dismiss sheet).
 */
@Composable
internal fun MiniBrowserTopBar(barColor: Color, onClose: () -> Unit) {
    val zen = LocalZenColors.current
    Column(Modifier.fillMaxWidth().background(barColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(zen.lift, DockShape)
                    .border(liftBorder(), DockShape)
                    .barClickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.browser_close),
                    tint = zen.ink.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(zen.ink.copy(alpha = 0.10f)),
        )
    }
}

/** Height of the chrome at rest, so the page can reserve exactly that much. */
internal val CollapsedBarHeightBase: androidx.compose.ui.unit.Dp =
    TopGap + BarHeight + RowGap + DockSide - BottomLift

// MARK: - Navigation bar

@Composable
private fun NavBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    effectiveUrl: String,
    title: String,
    isLoading: Boolean,
    progress: Float,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onBeginAddressEdit: () -> Unit,
    barColor: Color,
) {
    val zen = LocalZenColors.current
    Box(modifier = Modifier.fillMaxWidth().pane(DockShape, zen.lift)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavGlyph(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.browser_back),
                enabled = canGoBack,
                onClick = onBack,
            )
            NavGlyph(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                description = stringResource(R.string.browser_forward),
                enabled = canGoForward,
                onClick = onForward,
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(BarHeight - 12.dp)
                    .barClickable(onClick = onBeginAddressEdit)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteIcon(effectiveUrl = effectiveUrl)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title.ifBlank { stringResource(R.string.browser_search_placeholder) },
                    style = ZenType.rounded(16, FontWeight.SemiBold),
                    color = zen.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            NavGlyph(
                icon = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                description = stringResource(
                    if (isLoading) R.string.browser_stop else R.string.browser_reload,
                ),
                enabled = true,
                onClick = if (isLoading) onStop else onReload,
            )
        }

        if (isLoading && progress < 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(progress.coerceAtLeast(0.05f))
                    .height(2.dp)
                    .background(zen.coral),
            )
        }
    }
}

/** Icon-only glyph for the navigation bar: no chrome of its own. */
@Composable
private fun NavGlyph(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = BarHeight - 12.dp)
            .barClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = if (enabled) 0.8f else 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Dock

@Composable
private fun Dock(menuOpen: Boolean, ink: Color, onToggleMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DockInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Placeholder: the page stack arrives with real tabs.
        DockButton(
            icon = Icons.Outlined.FilterNone,
            description = stringResource(R.string.browser_tabs),
            tint = ink,
        ) {}

        Spacer(Modifier.weight(1f))

        // Placeholder: a new tab / fresh search arrives later.
        DockButton(
            icon = Icons.Outlined.Add,
            description = stringResource(R.string.browser_new_tab),
            tint = ink,
            width = PlusWidth,
            height = PlusHeight,
        ) {}

        Spacer(Modifier.weight(1f))

        DockButton(
            icon = Icons.Outlined.MoreHoriz,
            description = stringResource(if (menuOpen) R.string.browser_close_menu else R.string.browser_more),
            tint = ink,
            onClick = onToggleMenu,
        )
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    width: androidx.compose.ui.unit.Dp = DockSide,
    height: androidx.compose.ui.unit.Dp = DockSide,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(zen.lift, DockShape)
            .border(liftBorder(), DockShape)
            .barClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - Composer

@Composable
private fun ComposerBar(
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onAddressFocusedChange: (Boolean) -> Unit,
    onSubmitAddress: (String) -> Unit,
    onCancel: () -> Unit,
    barColor: Color,
) {
    val zen = LocalZenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pane(DockShape, zen.lift)
            .padding(horizontal = 6.dp)
            .height(BarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            // Same ink as the other glyphs: the brand color reads as a state.
            tint = zen.ink.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 8.dp).size(17.dp),
        )
        BasicTextField(
            value = addressText,
            onValueChange = onAddressTextChange,
            singleLine = true,
            textStyle = ZenType.rounded(16, FontWeight.Medium).copy(color = zen.ink),
            cursorBrush = SolidColor(zen.coral),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmitAddress(addressText.text) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onAddressFocusedChange(it.isFocused) },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (addressText.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browser_search_placeholder),
                            style = ZenType.rounded(16, FontWeight.Medium),
                            color = zen.ink.copy(alpha = 0.5f),
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (addressText.text.isNotEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Cancel,
                contentDescription = stringResource(R.string.browser_clear_search),
                tint = zen.ink.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(32.dp)
                    .barClickable { onAddressTextChange(TextFieldValue("", selection = TextRange.Zero)) }
                    .padding(8.dp),
            )
        }
        NavGlyph(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.common_cancel),
            enabled = true,
            onClick = onCancel,
        )
    }
}

// MARK: - Menu

@Composable
private fun ExpandedMenu(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
) {
    val zen = LocalZenColors.current
    Column(Modifier.fillMaxWidth()) {
        MenuRow(
            icon = Icons.Outlined.ContentCopy,
            title = stringResource(if (state.copyConfirmed) R.string.browser_copied else R.string.browser_copy_link),
            tint = if (state.copyConfirmed) zen.coral else zen.ink,
            onClick = onCopyLink,
        )
        RowDivider()
        MenuRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.browser_reader),
            tint = zen.ink,
            trailing = {
                when {
                    state.readerLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = zen.coral,
                    )

                    state.readerArticle != null -> Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = zen.coral,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            onClick = viewModel::toggleReader,
        )
        RowDivider()
        MenuRow(
            icon = Icons.Outlined.Laptop,
            title = stringResource(R.string.browser_desktop_site),
            tint = zen.ink,
            trailing = {
                Switch(
                    checked = state.requestsDesktopSite,
                    onCheckedChange = { viewModel.toggleDesktopSite() },
                )
            },
            onClick = viewModel::toggleDesktopSite,
        )
        RowDivider()
        MenuRow(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.browser_page_zoom),
            tint = zen.ink,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(zen.lift, CircleShape)
                        .padding(horizontal = 2.dp),
                ) {
                    ZoomStep(
                        icon = Icons.Outlined.KeyboardArrowDown,
                        description = stringResource(R.string.browser_zoom_out),
                        enabled = MiniBrowserZoom.canZoomOut(state.pageZoom),
                        onClick = viewModel::zoomOut,
                    )
                    Text(
                        text = MiniBrowserZoom.percentLabel(state.pageZoom),
                        style = ZenType.rounded(15, FontWeight.SemiBold),
                        color = zen.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(54.dp),
                    )
                    ZoomStep(
                        icon = Icons.Outlined.KeyboardArrowUp,
                        description = stringResource(R.string.browser_zoom_in),
                        enabled = MiniBrowserZoom.canZoomIn(state.pageZoom),
                        onClick = viewModel::zoomIn,
                    )
                }
            },
            onClick = {},
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MenuTile(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.browser_find_short),
                description = stringResource(R.string.browser_find_on_page),
                tint = zen.ink,
                onClick = onFind,
            )
            MenuTile(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.browser_share),
                description = stringResource(R.string.browser_share),
                tint = zen.ink,
                onClick = onShare,
            )
            MenuTile(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.browser_open_short),
                description = stringResource(R.string.browser_open_in_browser),
                tint = zen.ink,
                onClick = onOpenExternal,
            )
            MenuTile(
                icon = Icons.Outlined.PushPin,
                title = stringResource(
                    if (state.saveKind == SaveKind.NORMAL) R.string.browser_save_short else R.string.browser_pin_short,
                ),
                description = stringResource(
                    if (state.saveKind == SaveKind.NORMAL) R.string.browser_save_to_space else R.string.browser_pin_to_space,
                ),
                tint = zen.coral,
                onClick = onPin,
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .barClickable(onClick = onClick)
            .height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = ZenType.rounded(16, FontWeight.Medium),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        trailing?.invoke()
    }
}

@Composable
private fun RowDivider() {
    val zen = LocalZenColors.current
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 34.dp)
            .height(0.5.dp)
            .background(zen.ink.copy(alpha = 0.08f)),
    )
}

@Composable
private fun ZoomStep(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .barClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = if (enabled) 0.9f else 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RowScope.MenuTile(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .height(TileHeight)
            .background(zen.lift, TileShape)
            .border(liftBorder(), TileShape)
            .barClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = ZenType.rounded(11, FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SiteIcon(effectiveUrl: String) {
    val zen = LocalZenColors.current
    val icon = when {
        effectiveUrl.isEmpty() -> Icons.Outlined.Search
        effectiveUrl.startsWith("http://") -> Icons.Outlined.Lock
        effectiveUrl.startsWith("https://") -> Icons.Outlined.Lock
        else -> Icons.Outlined.Language
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (effectiveUrl.startsWith("http://")) zen.coral else zen.ink.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp),
    )
}
