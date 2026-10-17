package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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

/** Side controls: rounder than the app's tiles, still square with a flat edge. */
private val ControlShape = RoundedCornerShape(20.dp)

/** Menu tiles keep the app's own tile corner, like the home action bar. */
private val TileShape = RoundedCornerShape(16.dp)
private val ControlSide = 44.dp
private val RowHeight = 48.dp
private val TileHeight = 68.dp

/**
 * Bottom browser bar: one plate holding the controls, the open page menu and
 * the address composer (port of iOS `MiniBrowserChrome`).
 *
 * Collapsed it shows back, forward (when available), the address pill and the
 * menu control. The menu grows inside the same plate, so the bar and the menu
 * are one surface. The plate takes the page's own background color; the bar is
 * wrapped in `ZenTheme(darkTheme = isDark)` by the screen, so every color here
 * follows the plate rather than the system theme.
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
    onClose: () -> Unit,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val menuOpen = state.menuExpanded
    val plateShape = RoundedCornerShape(topStart = if (menuOpen) 28.dp else 0.dp, topEnd = if (menuOpen) 28.dp else 0.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The page's own color, nudged by the caller so the plate stays
            // visible against it; the lift shadow does the rest.
            .shadow(8.dp, plateShape, clip = false)
            .clip(plateShape)
            .background(barColor),
    ) {
        // Top edge, so the plate reads as chrome even on a same-colored page.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(LocalZenColors.current.ink.copy(alpha = 0.10f)),
        )
        if (menuOpen) {
            ExpandedMenu(
                state = state,
                viewModel = viewModel,
                onCopyLink = onCopyLink,
                onFind = onFind,
                onShare = onShare,
                onOpenExternal = onOpenExternal,
                onPin = onPin,
                onClose = onClose,
            )
        }

        if (state.isAddressFocused) {
            ComposerRow(
                addressText = addressText,
                onAddressTextChange = onAddressTextChange,
                focusRequester = focusRequester,
                onAddressFocusedChange = onAddressFocusedChange,
                onSubmitAddress = onSubmitAddress,
                onCancel = onCancelAddressEdit,
            )
        } else {
            ControlRow(
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                effectiveUrl = effectiveUrl,
                addressText = addressText,
                menuOpen = menuOpen,
                isLoading = state.isLoading,
                progress = state.progress,
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onStop = onStop,
                onBeginAddressEdit = onBeginAddressEdit,
                onToggleMenu = {
                    if (menuOpen) viewModel.collapseMenu() else viewModel.toggleMenu()
                },
            )
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

// MARK: - Collapsed row

@Composable
private fun ControlRow(
    canGoBack: Boolean,
    canGoForward: Boolean,
    effectiveUrl: String,
    addressText: TextFieldValue,
    menuOpen: Boolean,
    isLoading: Boolean,
    progress: Float,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onBeginAddressEdit: () -> Unit,
    onToggleMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = if (menuOpen) 16.dp else 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            description = stringResource(R.string.browser_back),
            enabled = canGoBack,
            onClick = onBack,
        )

        if (canGoForward) {
            ControlButton(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                description = stringResource(R.string.browser_forward),
                enabled = true,
                onClick = onForward,
            )
        }

        AddressPill(
            effectiveUrl = effectiveUrl,
            addressText = addressText,
            isLoading = isLoading,
            progress = progress,
            modifier = Modifier.weight(1f),
            onTap = onBeginAddressEdit,
            onReload = onReload,
            onStop = onStop,
        )

        ControlButton(
            icon = if (menuOpen) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
            description = stringResource(if (menuOpen) R.string.browser_close_menu else R.string.browser_more),
            enabled = true,
            onClick = onToggleMenu,
        )
    }
}

/** Side control: lift fill, squircle, icon only. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .size(ControlSide)
            .background(zen.lift, ControlShape)
            .border(liftBorder(), ControlShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = if (enabled) 0.75f else 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Address field: a pill, the one solid control in the row. */
@Composable
private fun AddressPill(
    effectiveUrl: String,
    addressText: TextFieldValue,
    isLoading: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = modifier
            .height(ControlSide)
            .background(zen.lift, CircleShape)
            .border(liftBorder(), CircleShape)
            .clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteIcon(effectiveUrl = effectiveUrl)
            Spacer(Modifier.width(8.dp))
            Text(
                text = addressText.text.ifEmpty { stringResource(R.string.browser_search_placeholder) },
                style = ZenType.rounded(16, FontWeight.Medium),
                color = if (addressText.text.isEmpty()) zen.ink.copy(alpha = 0.55f) else zen.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (effectiveUrl.isNotEmpty()) {
                Icon(
                    imageVector = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                    contentDescription = stringResource(
                        if (isLoading) R.string.browser_stop else R.string.browser_reload,
                    ),
                    tint = zen.ink.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = if (isLoading) onStop else onReload)
                        .padding(8.dp),
                )
            }
        }

        if (isLoading && progress < 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 1.dp)
                    .fillMaxWidth(progress.coerceAtLeast(0.05f))
                    .height(2.dp)
                    .background(zen.coral, CircleShape),
            )
        }
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

// MARK: - Composer

@Composable
private fun ComposerRow(
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onAddressFocusedChange: (Boolean) -> Unit,
    onSubmitAddress: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val zen = LocalZenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(ControlSide)
                .background(zen.lift, CircleShape)
                .border(liftBorder(), CircleShape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = zen.coral,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
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
                                color = zen.ink.copy(alpha = 0.55f),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (addressText.text.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.browser_clear_search),
                    tint = zen.ink.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onAddressTextChange(TextFieldValue("", selection = TextRange.Zero)) }
                        .padding(9.dp),
                )
            }
        }

        ControlButton(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.common_cancel),
            enabled = true,
            onClick = onCancel,
        )
    }
}

// MARK: - Expanded menu

@Composable
private fun ExpandedMenu(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
    onClose: () -> Unit,
) {
    val zen = LocalZenColors.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp)) {
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
                .padding(horizontal = 20.dp, vertical = 10.dp),
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

        RowDivider()
        MenuRow(
            icon = Icons.Outlined.Close,
            title = stringResource(R.string.browser_close),
            tint = MaterialTheme.colorScheme.error,
            onClick = onClose,
        )
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
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp)
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
            .padding(start = 52.dp)
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
            .clickable(enabled = enabled, onClick = onClick),
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
            .clickable(onClick = onClick),
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
