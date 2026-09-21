package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import de.kjell.zencompanion.favicon.FaviconResolver
import de.kjell.zencompanion.sync.ZenSpaces

/**
 * One tab's leading icon: synced Zen static icon (vector asset, emoji or data
 * URL), a gear for host-less `about:`/`chrome:` pages, or the favicon. Single
 * source for tab rows, split panes and essentials tiles (iOS: `ZenTabIcon`).
 */
@Composable
fun ZenTabIcon(
    tab: ZenSpaces.ZenTab,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    when {
        tab.hasStaticIcon == true && !tab.icon.isNullOrEmpty() -> {
            Box(modifier.size(size), contentAlignment = Alignment.Center) {
                ZenIconView(icon = tab.icon, size = size * 0.8f)
            }
        }
        FaviconResolver.isLocalURL(tab.url) -> {
            // about:/chrome: pages (e.g. Firefox Settings) have no web host;
            // render a static gear so they never break the row layout or
            // trigger a pointless favicon lookup.
            Box(modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(size * 0.6f),
                )
            }
        }
        else -> {
            Favicon(
                urlString = tab.url,
                directURL = tab.iconURL,
                size = size,
                modifier = modifier,
            )
        }
    }
}
