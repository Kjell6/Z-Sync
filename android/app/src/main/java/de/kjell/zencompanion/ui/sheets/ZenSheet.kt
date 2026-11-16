package de.kjell.zencompanion.ui.sheets

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gap between a sheet's drag handle and its first line of content. The handle
 * block already carries its own breathing room (Material's handle is 4dp tall
 * inside a 48dp target), so this stays small: the title follows a normal
 * distance under the handle instead of leaving a dead band.
 */
internal val SheetContentTopSpacing: Dp = 0.dp

/** Bottom breathing room of a sheet page, above the navigation-bar inset. */
internal val SheetContentBottomSpacing: Dp = 24.dp

/**
 * Root modifier for a page inside the settings sheet.
 *
 * `ModalBottomSheet` sizes itself to its content, so a short page ("Advanced",
 * "Add search engine") collapsed the sheet into a small card at the bottom of
 * the screen. Filling the height keeps every settings page full height, which
 * is also what iOS does.
 *
 * The modal sheet already applies the status-bar and navigation-bar insets to
 * its content column (`contentWindowInsets`), and the drag handle sits inside
 * that inset — so pages must not pad for the system bars again. Pages only add
 * [SheetContentTopSpacing] under the handle and [SheetContentBottomSpacing] at
 * the bottom.
 */
internal fun Modifier.settingsSheetPage(): Modifier = fillMaxWidth().fillMaxHeight()

/**
 * Native Material 3 modal sheet wrapper. Every sheet gets the standard
 * Material drag handle and the standard sheet top corners, so all of them
 * look and drag the same; callers can still override either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    // Sheets open to the expanded anchor and are shown by ModalBottomSheet;
    // a sheet that may rest half-open keeps the partial anchor available.
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = if (skipPartiallyExpanded) {
            setOf(SheetValue.Hidden, SheetValue.Expanded)
        } else {
            setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
        },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = shape,
        containerColor = containerColor,
        dragHandle = dragHandle,
        content = { content() },
    )
}
