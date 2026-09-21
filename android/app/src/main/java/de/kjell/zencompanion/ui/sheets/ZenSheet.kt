package de.kjell.zencompanion.ui.sheets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

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
