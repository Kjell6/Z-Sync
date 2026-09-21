package de.kjell.zencompanion.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.components.PinDestinationPicker
import de.kjell.zencompanion.ui.components.PinDestinationPickerStyle
import de.kjell.zencompanion.ui.components.ZenSpaceGradientBackground
import de.kjell.zencompanion.ui.motion.DampingShareResult
import de.kjell.zencompanion.ui.motion.StiffnessShareResult
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.util.Haptics

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import de.kjell.zencompanion.ui.theme.ZenTheme

enum class SharePhase { loading, signedOut, failed, saved, pick, saving }

/** Capsule (fully-rounded pill) used for the share buttons. */
private val Capsule = RoundedCornerShape(50)


/**
 * Port of `ShareSheetView` (the iOS Share Extension): phases loading /
 * signed-out / failed / saved / pick / saving; background is the gradient of
 * the selected space; save button is a full-width ink capsule; success shows a
 * checkmark and auto-closes after 480ms.
 */
@Composable
fun ShareScreen(
    url: String?,
    pageTitle: String,
    spaces: List<ZenSpaces.ZenSpace>,
    selected: PinDestination?,
    phase: SharePhase,
    errorText: String?,
    saveKind: SaveKind = SaveKind.PINNED,
    savedAsPinnedFallback: Boolean = false,
    onSelectDestination: (PinDestination) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedSpace = spaces.firstOrNull { it.id == selected?.spaceId }
    val isDark = selectedSpace?.theme?.isDarkTheme ?: isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Background = gradient of the SELECTED space, including system bars.
        ZenSpaceGradientBackground(theme = selectedSpace?.theme, darkenDots = isDark)

        ZenTheme(darkTheme = isDark) {
            val colors = LocalZenColors.current
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
            when (phase) {
                SharePhase.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.coral)
                }

                SharePhase.signedOut -> StatusView(
                    title = stringResource(R.string.share_signed_out_title),
                    detail = stringResource(R.string.share_signed_out_detail),
                    actionLabel = stringResource(R.string.common_done),
                    onAction = onCancel,
                )

                SharePhase.failed -> StatusView(
                    title = stringResource(R.string.share_failed_title),
                    detail = errorText ?: stringResource(R.string.error_generic),
                    actionLabel = stringResource(R.string.common_done),
                    onAction = onCancel,
                )

                SharePhase.saved -> {
                    val savedName = if (selectedSpace != null && selected != null) {
                        PinDestinationModel.displayName(destination = selected, space = selectedSpace)
                    } else {
                        stringResource(R.string.share_workspace)
                    }
                    StatusView(
                        title = stringResource(R.string.share_saved_title),
                        detail = if (savedAsPinnedFallback) {
                            stringResource(R.string.save_fallback_normal_off)
                        } else {
                            stringResource(R.string.share_saved_detail, savedName)
                        },
                        systemImageRes = R.drawable.ic_checkmark,
                    )
                }

                SharePhase.pick, SharePhase.saving -> Picker(
                    url = url,
                    pageTitle = pageTitle,
                    spaces = spaces,
                    selected = selected,
                    saving = phase == SharePhase.saving,
                    hideFolders = saveKind == SaveKind.NORMAL,
                    saveKind = saveKind,
                    onSelectDestination = onSelectDestination,
                    onSave = onSave,
                    onCancel = onCancel,
                )
            }
            }
        }
    }
}

@Composable
private fun StatusView(
    title: String,
    detail: String,
    systemImageRes: Int? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = LocalZenColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        if (systemImageRes != null) {
            Image(
                painter = painterResource(systemImageRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.ink),
                modifier = Modifier
                    .size(34.dp)
                    .padding(bottom = 2.dp),
            )
        }
        Text(
            title,
            style = ZenType.rounded(22, FontWeight.SemiBold),
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            detail,
            style = ZenType.rounded(16, FontWeight.Normal),
            color = colors.ink62,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        if (actionLabel != null) {
            Button(
                onClick = onAction,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper,
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = ZenType.rounded(17, FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Picker(
    url: String?,
    pageTitle: String,
    spaces: List<ZenSpaces.ZenSpace>,
    selected: PinDestination?,
    saving: Boolean,
    hideFolders: Boolean,
    saveKind: SaveKind,
    onSelectDestination: (PinDestination) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalZenColors.current
    val context = LocalContext.current
    val selectedSpace = spaces.firstOrNull { it.id == selected?.spaceId }
    val canSave = selectedSpace != null && url != null && !saving

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Spacer(Modifier.height(20.dp).weight(0.6f))

        Favicon(urlString = url ?: "", size = 72.dp)
        Spacer(Modifier.height(18.dp))

        Text(
            text = headlineTitle(pageTitle, url),
            style = ZenType.rounded(22, FontWeight.SemiBold),
            color = colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 28.dp),
        )

        url?.let { urlText ->
            Text(
                text = urlText,
                style = ZenType.rounded(14, FontWeight.Normal),
                color = colors.ink45,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp).padding(top = 6.dp),
            )
        }

        // Destination picker capsule backed by a dropdown menu (port of the
        // iOS PinDestinationMenuButton): spaces on top, divider, then a
        // "Pin to Folder…" item that opens a nested menu with per-space
        // groups where folders appear as indented plain-text rows.
        var menuOpen by remember { mutableStateOf(false) }
        var folderMenuOpen by remember { mutableStateOf(false) }
        Box {
            PinDestinationPicker(
                spaces = spaces,
                selected = selected,
                isSpaceMenuOpen = menuOpen,
                isFolderMenuOpen = folderMenuOpen,
                onSelect = { destination ->
                    Haptics.perform(context, Haptics.Kind.SELECTION)
                    onSelectDestination(destination)
                },
                onSpaceMenuOpenChange = { menuOpen = it },
                onFolderMenuOpenChange = { folderMenuOpen = it },
                onMenuWillOpen = {},
                onMenuDidDismiss = {},
                style = PinDestinationPickerStyle.ShareCapsule,
                hideFolders = hideFolders,
            )
        }

        Spacer(Modifier.weight(1f))

        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp),
        ) {
            // Save: native button with the app's capsule look (disabled at 28% ink).
            Button(
                onClick = onSave,
                enabled = canSave && !saving,
                shape = Capsule,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper,
                    disabledContainerColor = colors.ink28,
                    disabledContentColor = colors.paper,
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = colors.paper, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stringResource(
                            if (saveKind == SaveKind.NORMAL) R.string.share_save_normal else R.string.share_save,
                        ),
                        style = ZenType.rounded(17, FontWeight.SemiBold),
                    )
                }
            }

            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.ink50),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    style = ZenType.rounded(16, FontWeight.Medium),
                )
            }
        }
    }
}

/** Port of `headlineTitle`: page title unless it equals host/url, else host. */
internal fun headlineTitle(pageTitle: String, url: String?): String {
    val raw = pageTitle.trim()
    val host = runCatching { android.net.Uri.parse(url.orEmpty()).host }.getOrNull() ?: ""
    if (raw.isNotEmpty() && raw != host && raw != url) return raw
    if (host.isNotEmpty()) return host
    return url ?: "Tab"
}
