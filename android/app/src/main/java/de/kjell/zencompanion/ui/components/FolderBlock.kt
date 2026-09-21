package de.kjell.zencompanion.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.motion.DampingFolderBody
import de.kjell.zencompanion.ui.motion.StiffnessFolderBody

/**
 * Native Material 3 Folder Block.
 * Expandable folder showing nested tabs with fluid expansion.
 */
private val expandedIndent = 16.dp

@Composable
fun FolderBlock(
    folder: ZenSpaces.ZenFolder,
    onDeleteTab: (String) -> Unit,
    onOpenUrl: ((String, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fallbackName = stringResource(R.string.tabs_folder)
    var isExpanded by remember { mutableStateOf(false) }

    // Horizontal padding comes from the CALLER (top-level = 20.dp, matching
    // the tab rows). Nested FolderBlocks receive no extra padding, so tabs
    // and sub-folders on the same nesting level share the same indent —
    // each nesting level adds exactly `expandedIndent` for both.
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isExpanded = !isExpanded },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Horizontal inset comes from the caller (20dp), matching
                        // the space header; no extra internal padding.
                        .padding(vertical = 9.dp),
                ) {
                    // Icon slot only; the silhouette is the overlay below.
                    Spacer(Modifier.size(28.dp))

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = folder.name.ifEmpty { fallbackName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The folder silhouette is always the row's icon; a user-chosen
            // icon renders inside it, exactly like Zen Desktop's folder SVG.
            // Replacing the silhouette made folders indistinguishable from tabs.
            // Drawn as a sibling ABOVE the clickable Surface (not as its
            // content): folding open skews the back shape ~2dp past the 28dp
            // slot's left edge and Surface clips content to its bounds, which
            // sliced the open folder off at the row edge.
            ZenFolderSidebarIcon(
                isExpanded = isExpanded,
                userIcon = folder.icon
                    ?.takeIf { it.isNotEmpty() && it != "📁" && it != "📂" },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(spring(dampingRatio = DampingFolderBody, stiffness = StiffnessFolderBody)) +
                expandVertically(spring(dampingRatio = DampingFolderBody, stiffness = StiffnessFolderBody)),
            exit = fadeOut(spring(dampingRatio = DampingFolderBody, stiffness = StiffnessFolderBody)) +
                shrinkVertically(spring(dampingRatio = DampingFolderBody, stiffness = StiffnessFolderBody)),
        ) {
            // iOS parity (SpacesBrowserView.FolderBlock): tabs and sub-folders
            // get the SAME leading indent. Recursion provides the depth; no
            // extra padding is stacked onto the sub-folder rows.
            Column(modifier = Modifier.padding(start = expandedIndent)) {
                for (tab in folder.tabs) {
                    TabRow(
                        tab = tab,
                        deletable = true,
                        modifier = Modifier.fillMaxWidth(),
                        onDelete = { onDeleteTab(tab.id) },
                        onOpenUrl = onOpenUrl,
                    )
                }
                for (subfolder in folder.subfolders ?: emptyList()) {
                    FolderBlock(
                        folder = subfolder,
                        onDeleteTab = onDeleteTab,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        }
    }
}
