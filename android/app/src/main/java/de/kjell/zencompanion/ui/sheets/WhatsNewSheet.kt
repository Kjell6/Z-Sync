package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.kjell.zencompanion.ui.theme.ZenPrimaryButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.WhatsNewRelease
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.liftCard

/**
 * Material 3 What's New sheet: one short, scrollable release note with a
 * hero image, a short row of feature pills and a pinned Done button. There is
 * no close button: Done or a swipe down dismisses the sheet. All type is sized in sp so font scaling is respected; the pills
 * wrap onto more lines at large font scales and the content scrolls instead
 * of clipping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val zen = LocalZenColors.current

    ZenSheet(
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(WhatsNewRelease.headlineRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = zen.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(WhatsNewRelease.subtitleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = zen.ink.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                Hero()

                Spacer(Modifier.height(24.dp))

                Features()

                Spacer(Modifier.height(24.dp))
            }

            ZenPrimaryButton(
                text = stringResource(R.string.whats_new_done),
                onClick = onDismiss,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

/**
 * The hero image, full width at its own aspect ratio. It is decorative apart
 * from one description for TalkBack.
 */
@Composable
private fun Hero() {
    val description = stringResource(WhatsNewRelease.heroDescriptionRes)
    val painter = painterResource(WhatsNewRelease.heroImageRes)
    val size = painter.intrinsicSize

    Image(
        painter = painter,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(size.width / size.height)
            .clip(RoundedCornerShape(26.dp)),
    )
}

/** A caption and the remaining features as small pills that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Features() {
    val zen = LocalZenColors.current

    Text(
        text = stringResource(WhatsNewRelease.featuresHeadingRes),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = zen.ink.copy(alpha = 0.65f),
        modifier = Modifier.semantics { heading() },
    )

    Spacer(Modifier.height(10.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WhatsNewRelease.items.forEach { item ->
            Text(
                text = stringResource(item.titleRes),
                fontSize = 14.sp,
                color = zen.ink,
                modifier = Modifier
                    .liftCard(CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
