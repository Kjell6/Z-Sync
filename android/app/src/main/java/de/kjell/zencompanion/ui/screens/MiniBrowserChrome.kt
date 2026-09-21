package de.kjell.zencompanion.ui.screens

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R

@Composable
internal fun MiniBrowserChrome(
    isAddressFocused: Boolean,
    onAddressFocusedChange: (Boolean) -> Unit,
    textFieldValue: TextFieldValue,
    onTextInputChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    effectiveUrl: String,
    currentUrl: String,
    initialUrl: String?,
    webView: WebView?,
    isLoading: Boolean,
    progress: Float,
    isDark: Boolean,
    onBgColor: Color,
    onVariantBgColor: Color,
    onSubmitAddress: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isAddressFocused) {
        if (isAddressFocused) {
            val fullText = webView?.url ?: currentUrl.ifEmpty { initialUrl ?: "" }
            if (fullText.isNotEmpty()) {
                onTextInputChange(
                    TextFieldValue(
                        text = fullText,
                        selection = TextRange(0, fullText.length),
                    )
                )
            }
        }
    }

    // Tint against the sampled page/chrome background, not the app paper theme
    // (`surfaceContainerHigh` is beige and clashes on a white Wikipedia header).
    val pillBgColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.06f)
    val pillBorderColor = if (isAddressFocused) {
        MaterialTheme.colorScheme.primary
    } else if (isDark) {
        Color.White.copy(alpha = 0.22f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    // Header Bar (Symmetric full-width address bar with animated Cancel button on focus)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Smart URL Pill
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            color = pillBgColor,
            border = if (isAddressFocused) {
                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, pillBorderColor)
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Leading Icon (Security / Search)
                val leadingIcon = when {
                    isAddressFocused -> Icons.Outlined.Search
                    effectiveUrl.startsWith("https://", ignoreCase = true) -> Icons.Outlined.Lock
                    effectiveUrl.startsWith("http://", ignoreCase = true) -> Icons.Outlined.Lock
                    effectiveUrl.isNotEmpty() -> Icons.Outlined.Language
                    else -> Icons.Outlined.Search
                }
                val leadingTint = when {
                    isAddressFocused -> MaterialTheme.colorScheme.primary
                    effectiveUrl.startsWith("https://", ignoreCase = true) -> onVariantBgColor
                    effectiveUrl.startsWith("http://", ignoreCase = true) -> MaterialTheme.colorScheme.error
                    else -> onVariantBgColor
                }
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(18.dp),
                )

                Spacer(Modifier.width(10.dp))

                // Text Field / Domain Display
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        onTextInputChange(newValue)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            val nowFocused = focusState.isFocused
                            if (nowFocused != isAddressFocused) {
                                onAddressFocusedChange(nowFocused)
                                if (nowFocused) {
                                    val fullText = webView?.url ?: currentUrl.ifEmpty { initialUrl ?: "" }
                                    onTextInputChange(
                                        TextFieldValue(
                                            text = fullText,
                                            selection = TextRange(0, fullText.length),
                                        )
                                    )
                                } else {
                                    val latestUrl = webView?.url ?: currentUrl.ifEmpty { initialUrl ?: "" }
                                    val host = runCatching { Uri.parse(latestUrl).host?.removePrefix("www.") }.getOrNull()
                                    val display = if (!host.isNullOrEmpty()) host else latestUrl
                                    onTextInputChange(
                                        TextFieldValue(
                                            text = display,
                                            selection = TextRange.Zero,
                                        )
                                    )
                                }
                            }
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = onBgColor,
                        textAlign = if (isAddressFocused) TextAlign.Start else TextAlign.Center,
                        fontWeight = if (isAddressFocused) FontWeight.Normal else FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Go,
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { onSubmitAddress(textFieldValue.text) },
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (isAddressFocused) Alignment.CenterStart else Alignment.Center,
                        ) {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.browser_search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onVariantBgColor.copy(alpha = 0.6f),
                                    textAlign = if (isAddressFocused) TextAlign.Start else TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                // Trailing Icon (Clear when focused & typing, Reload/Stop when unfocused)
                if (isAddressFocused && textFieldValue.text.isNotEmpty()) {
                    IconButton(
                        onClick = { onTextInputChange(TextFieldValue(text = "", selection = TextRange.Zero)) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.browser_clear_search),
                            tint = onVariantBgColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else if (!isAddressFocused && effectiveUrl.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (isLoading) {
                                webView?.stopLoading()
                            } else {
                                webView?.reload()
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                            contentDescription = stringResource(if (isLoading) R.string.browser_stop else R.string.browser_reload),
                            tint = onVariantBgColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Animated Cancel Button on Focus
        if (isAddressFocused) {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.common_cancel),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // Loading Progress Bar
    if (isLoading && progress < 1f) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    } else {
        Spacer(Modifier.height(2.5.dp))
    }

    // Subtle top stroke divider matching the bottom bar
    androidx.compose.material3.HorizontalDivider(
        color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f),
    )
}
