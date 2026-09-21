package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import de.kjell.zencompanion.favicon.FaviconLoader
import de.kjell.zencompanion.favicon.FaviconResolver
import de.kjell.zencompanion.ui.theme.FaviconPlaceholder
import de.kjell.zencompanion.ui.theme.FaviconPlaceholderDark
import de.kjell.zencompanion.ui.theme.LocalZenColors

/**
 * Port of `Favicon` (Shared/ZenTheme.swift): bare favicon with continuous
 * rounded corners (`size * 0.22`), plain gray rounded square when no icon is
 * available. No background box, no fallback glyph.
 */
@Composable
fun Favicon(
    urlString: String,
    modifier: Modifier = Modifier,
    directURL: String? = null,
    size: Dp,
) {
    val resolved = FaviconResolver.url(pageURL = urlString, directURL = directURL)
    val colors = LocalZenColors.current

    var image by remember(resolved) { mutableStateOf(resolved?.let { FaviconLoader.cached(it) }) }
    LaunchedEffect(resolved) {
        if (resolved == null) {
            image = null
            return@LaunchedEffect
        }
        // Cached icons are applied synchronously: no loader hop, no
        // placeholder flash when a pager page is rebuilt on a space switch.
        image = FaviconLoader.cached(resolved) ?: FaviconLoader.image(resolved)
    }

    val shape = RoundedCornerShape(size * 0.22f)
    // iOS parity: the loaded image is rendered bare (transparent), so round
    // favicons keep their transparent corners. The gray placeholder square
    // only backs the icon while/when no image is available.
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (image == null) {
                    Modifier.background(if (colors.isDark) FaviconPlaceholderDark else FaviconPlaceholder)
                } else {
                    Modifier
                },
            ),
    ) {
        image?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}
