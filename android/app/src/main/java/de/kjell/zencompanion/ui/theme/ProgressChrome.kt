package de.kjell.zencompanion.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The Expressive loading indicator: the shape-morphing Material indicator,
 * the replacement for an indeterminate [androidx.compose.material3.CircularProgressIndicator].
 * The caller's size drives it, so a 16dp slot in a tab row and a 48dp slot on
 * a page both work. Color defaults to the app's primary.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ZenLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}
