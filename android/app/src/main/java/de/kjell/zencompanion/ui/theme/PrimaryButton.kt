package de.kjell.zencompanion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full-width call to action at the bottom of the onboarding and the
 * What's New sheet: a capsule in the brand colour. Mirrors iOS
 * `PrimaryCapsuleButton`.
 */
@Composable
fun ZenPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val zen = LocalZenColors.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(zen.coral)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // Paper on the dark brand, near-black on the light dark-mode brand.
            color = zen.paper,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
