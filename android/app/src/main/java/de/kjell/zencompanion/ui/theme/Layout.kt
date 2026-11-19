package de.kjell.zencompanion.ui.theme

import androidx.compose.ui.unit.dp

/** Layout limits shared by the screens. */
object ZenLayout {
    /**
     * The widest the home screen's content and the browser's controls get.
     * On a tablet or an unfolded foldable they keep to a centred column of
     * this width instead of running from edge to edge; only backgrounds reach
     * the edges. Phones are narrower (about 360 to 450dp), so nothing changes
     * there. The same value as iOS's `SpacesLayout.maxWidth`.
     */
    val MaxContentWidth = 560.dp
}
