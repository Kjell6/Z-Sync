package de.kjell.zencompanion.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.ui.components.openURLExternally

@Composable
internal fun MiniBrowserFooter(
    animatedBgColor: Color,
    isDark: Boolean,
    webView: WebView?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    effectiveUrl: String,
    canPin: Boolean,
    onBgColor: Color,
    onVariantBgColor: Color,
    onPin: () -> Unit,
    onClose: () -> Unit,
    saveKind: SaveKind = SaveKind.PINNED,
) {
    val context = LocalContext.current

    // Footer Toolbar (Adaptive theme color, 0dp tonal elevation to prevent reddish surfaceTint)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = animatedBgColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            androidx.compose.material3.HorizontalDivider(
                color = if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left Group: Back / Forward
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { webView?.goBack() },
                        enabled = canGoBack,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.browser_back),
                            tint = if (canGoBack) onBgColor else onVariantBgColor.copy(alpha = 0.38f),
                        )
                    }

                    IconButton(
                        onClick = { webView?.goForward() },
                        enabled = canGoForward,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = stringResource(R.string.browser_forward),
                            tint = if (canGoForward) onBgColor else onVariantBgColor.copy(alpha = 0.38f),
                        )
                    }
                }

                // Right Group: External Browser / Pin to Space / Close
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            if (effectiveUrl.isNotEmpty()) {
                                openURLExternally(context, effectiveUrl)
                            }
                        },
                        enabled = effectiveUrl.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.browser_open_in_browser),
                            tint = if (effectiveUrl.isNotEmpty()) onBgColor else onVariantBgColor.copy(alpha = 0.38f),
                        )
                    }

                    IconButton(
                        onClick = onPin,
                        enabled = canPin && effectiveUrl.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = stringResource(
                                if (saveKind == SaveKind.NORMAL) {
                                    R.string.browser_save_to_space
                                } else {
                                    R.string.browser_pin_to_space
                                },
                            ),
                            tint = if (canPin && effectiveUrl.isNotEmpty()) MaterialTheme.colorScheme.primary else onVariantBgColor.copy(alpha = 0.38f),
                        )
                    }

                    IconButton(
                        onClick = onClose,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.browser_close),
                            tint = onBgColor,
                        )
                    }
                }
            }
        }
    }
}
