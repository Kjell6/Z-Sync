package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.components.PinDestinationPicker
import de.kjell.zencompanion.util.Haptics
import kotlinx.coroutines.delay

/** One ViewModel per screen; a launch key re-arms it instead of accumulating. */
private const val MINI_BROWSER_VIEW_MODEL_KEY = "mini-browser"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiniBrowserScreen(
    initialUrl: String?,
    initialTitle: String? = null,
    launchKey: Int,
    currentSpace: ZenSpaces.ZenSpace,
    allSpaces: List<ZenSpaces.ZenSpace>,
    browserRepository: BrowserRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val viewModel: MiniBrowserViewModel = viewModel(
        key = MINI_BROWSER_VIEW_MODEL_KEY,
        factory = MiniBrowserViewModel.Factory(
            repository = browserRepository,
            initialUrl = initialUrl,
            initialTitle = initialTitle,
            spaces = allSpaces,
            currentSpaceId = currentSpace.id,
        ),
    )
    val state by viewModel.state.collectAsState()

    LaunchedEffect(launchKey) {
        viewModel.startNewLaunch(
            launchKey = launchKey,
            initialUrl = initialUrl,
            initialTitle = initialTitle,
            spaces = allSpaces,
            currentSpaceId = currentSpace.id,
        )
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.commitPendingPinSave()
        }
    }

    // Auto-focus URL bar if opened without initial URL
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNullOrEmpty()) {
            delay(200)
            focusRequester.requestFocus()
        }
    }

    // Back handling
    BackHandler {
        when (viewModel.onBackPressed(webView?.canGoBack() == true)) {
            MiniBrowserViewModel.BackAction.Handled -> Unit
            MiniBrowserViewModel.BackAction.ClearAddressFocus -> focusManager.clearFocus()
            MiniBrowserViewModel.BackAction.GoBack -> webView?.goBack()
            MiniBrowserViewModel.BackAction.Dismiss -> onDismiss()
        }
    }

    val effectiveUrl = webView?.url ?: state.currentUrl.ifEmpty { initialUrl ?: "" }
    val defaultSurface = MaterialTheme.colorScheme.surface
    val targetBg = state.siteThemeColor ?: defaultSurface
    val animatedBgColor by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(300),
        label = "browserThemeColor",
    )
    val isDark = animatedBgColor.luminance() < 0.5f
    val onBgColor = if (isDark) Color(0xFFF2F2F5) else MaterialTheme.colorScheme.onSurface
    val onVariantBgColor = if (isDark) Color(0xFFB8B8C2) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxSize(),
        color = animatedBgColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            MiniBrowserChrome(
                isAddressFocused = state.isAddressFocused,
                onAddressFocusedChange = viewModel::onAddressFocusChange,
                textFieldValue = state.addressText,
                onTextInputChange = viewModel::onAddressTextChange,
                focusRequester = focusRequester,
                effectiveUrl = effectiveUrl,
                currentUrl = state.currentUrl,
                initialUrl = initialUrl,
                webView = webView,
                isLoading = state.isLoading,
                progress = state.progress,
                isDark = isDark,
                onBgColor = onBgColor,
                onVariantBgColor = onVariantBgColor,
                onSubmitAddress = { input ->
                    val target = viewModel.submitAddress(input)
                    if (target.isNotEmpty()) {
                        webView?.loadUrl(target)
                    }
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
            )

            // WebView & Floating Deferred Banner Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MiniBrowserWebView(
                    initialUrl = initialUrl,
                    isAddressFocused = state.isAddressFocused,
                    onWebViewCreated = { webView = it },
                    onLoadingChange = viewModel::onLoadingChange,
                    onSiteThemeColorChange = viewModel::onSiteThemeColorChange,
                    onCurrentUrlChange = viewModel::onCurrentUrlChange,
                    onCanGoBackChange = viewModel::onCanGoBackChange,
                    onCanGoForwardChange = viewModel::onCanGoForwardChange,
                    onTextInputChange = viewModel::onAddressTextChange,
                    onTitleChange = viewModel::onCurrentTitleChange,
                    onProgressChange = viewModel::onProgressChange,
                )

                // Deferred Space Pinning Banner (Floats above footer)
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.showPinBanner,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 20.dp, end = 20.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 8.dp,
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Left: Pin Icon + "Pinned"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(
                                        if (state.saveKind == SaveKind.NORMAL) {
                                            R.string.browser_normal
                                        } else {
                                            R.string.browser_pinned
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            VerticalDivider(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )

                            Spacer(Modifier.width(8.dp))

                            // Right: Destination Selector Dropdown (space +
                            // optional folder via "Pin to Folder…" submenu).
                            // Opening the menu cancels the banner timer so the
                            // banner stays alive while the user is choosing.
                            // Normal saves only offer space roots.
                            PinDestinationPicker(
                                spaces = allSpaces,
                                selected = state.pinnedDestination,
                                isSpaceMenuOpen = state.isSpaceMenuOpen,
                                isFolderMenuOpen = state.isFolderMenuOpen,
                                onSelect = { destination ->
                                    Haptics.perform(context, Haptics.Kind.SELECTION)
                                    viewModel.changePinDestination(destination)
                                },
                                onSpaceMenuOpenChange = viewModel::onSpaceMenuOpenChange,
                                onFolderMenuOpenChange = viewModel::onFolderMenuOpenChange,
                                onMenuWillOpen = viewModel::onMenuWillOpen,
                                onMenuDidDismiss = viewModel::onMenuDidDismiss,
                                hideFolders = state.hideFolders,
                            )
                        }
                    }
                }

                // Normal-save fallback notice (same banner styling, anchored
                // to the top so it never collides with the destination banner).
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.fallbackNotice,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 8.dp,
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.save_fallback_normal_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            MiniBrowserFooter(
                animatedBgColor = animatedBgColor,
                isDark = isDark,
                webView = webView,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                effectiveUrl = effectiveUrl,
                canPin = allSpaces.isNotEmpty(),
                saveKind = state.saveKind,
                onBgColor = onBgColor,
                onVariantBgColor = onVariantBgColor,
                onPin = {
                    Haptics.perform(context, Haptics.Kind.CONFIRM)
                    viewModel.triggerPinBanner()
                },
                onClose = {
                    viewModel.commitPendingPinSave()
                    onDismiss()
                },
            )
        }
    }
}
