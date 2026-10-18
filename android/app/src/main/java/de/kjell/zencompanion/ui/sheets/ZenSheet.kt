package de.kjell.zencompanion.ui.sheets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * Root modifier for a page inside the settings sheet.
 *
 * `ModalBottomSheet` sizes itself to its content, so a short page ("Advanced",
 * "Add search engine") collapsed the sheet into a small card at the bottom of
 * the screen. Filling the height keeps every settings page full height, which
 * is also what iOS does.
 */
internal fun Modifier.settingsSheetPage(): Modifier = fillMaxWidth().fillMaxHeight()

/**
 * Native Material 3 modal sheet wrapper (full-screen overlay without handle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    dragHandle: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShape,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = shape,
        containerColor = containerColor,
        dragHandle = dragHandle,
        content = { content() },
    )
}
